package com.stonewu.fusion.service.generation.consumer;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.infrastructure.queue.RedisTaskQueue;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategy;
import com.stonewu.fusion.service.storage.MediaStorageService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 生视频任务消费器
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoGenerationConsumer {

    private static final String BASE_QUEUE_NAME = "video_generation";
    private static final String MODEL_QUEUE_PREFIX = BASE_QUEUE_NAME + ":model:";
    private static final int MODEL_TYPE_VIDEO = 3;

    private final RedisTaskQueue taskQueue;
    private final VideoGenerationService videoGenerationService;
    private final AiModelService aiModelService;
    private final ApiConfigService apiConfigService;
    private final GenerationModelCapabilityService generationModelCapabilityService;
    private final List<VideoGenerationStrategy> strategies;
    private final MediaStorageService mediaStorageService;

    private final AtomicInteger workerThreadCounter = new AtomicInteger(1);
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "video-generation-worker-" + workerThreadCounter.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    private Map<String, VideoGenerationStrategy> strategyMap;

    private Map<String, VideoGenerationStrategy> getStrategyMap() {
        if (strategyMap == null) {
            strategyMap = strategies.stream()
                    .collect(Collectors.toMap(VideoGenerationStrategy::getName, s -> s));
        }
        return strategyMap;
    }

    /**
     * 提交生视频任务到队列
     */
    public String submitTask(VideoTask task) {
        AiModel queueModel = resolveQueueModel(task.getModelId());
        if (queueModel == null) {
            throw new BusinessException("没有可用的视频生成模型");
        }
        task.setModelId(queueModel.getId());

        String queueName = resolveQueueName(task.getModelId());
        String taskId = IdUtil.fastSimpleUUID();
        task.setTaskId(taskId);
        task.setStatus(0);
        videoGenerationService.create(task);

        refreshQueueMaxConcurrent(queueName, task.getModelId());

        for (int i = 0; i < task.getCount(); i++) {
            VideoItem item = VideoItem.builder()
                    .taskId(task.getId())
                    .status(0)
                    .build();
            videoGenerationService.createItem(item);
        }

        taskQueue.push(queueName, taskId);
        log.info("[VideoConsumer] 任务入队: taskId={}, queue={}, modelId={}", taskId, queueName, task.getModelId());
        return taskId;
    }

    /**
     * 提交任务并同步等待结果（阻塞当前线程）
     * <p>
     * 适用于 AI Agent 工具等需要同步获取生视频结果的场景。
     * 内部流程：submitTask() 入队 → Consumer 定时取出执行 → 本方法轮询 DB 等待完成。
     *
     * @param task      生视频任务（需设置好 prompt、generateMode、modelId 等）
     * @param timeoutMs 最大等待时间（毫秒）
     * @return 完成的 VideoTask（含结果视频在 VideoItem 中）
     * @throws RuntimeException 超时或任务失败时抛出
     * @throws InterruptedException 等待过程中线程被中断
     */
    public VideoTask submitAndWait(VideoTask task, long timeoutMs) throws InterruptedException {
        String taskId = submitTask(task);

        long deadline = System.currentTimeMillis() + timeoutMs;
        long pollInterval = 3000L;

        log.info("[VideoConsumer] 同步等待任务完成: taskId={}, timeout={}ms", taskId, timeoutMs);

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollInterval);

            VideoTask current = videoGenerationService.getByTaskId(taskId);
            switch (current.getStatus()) {
                case 2: // 已完成
                    log.info("[VideoConsumer] 任务已完成: taskId={}", taskId);
                    return current;
                case 3: // 失败
                    String errorMsg = current.getErrorMsg() != null ? current.getErrorMsg() : "未知错误";
                    throw new RuntimeException("生视频任务失败: " + errorMsg);
                default:
                    // 0-排队中 1-处理中，继续等待
                    break;
            }
        }

        // 同步等待超时：不再标记任务失败，让后台策略继续轮询直到完成
        // 任务在后台仍可能成功，可在「任务记录」里查看最终结果
        log.warn("[VideoConsumer] 同步等待超时但任务仍在后台运行: taskId={}, waited={}s", taskId, timeoutMs / 1000);
        throw new RuntimeException("生视频任务等待超过 " + (timeoutMs / 1000) + " 秒未完成，已转为后台生成。请稍后到「任务记录」查看结果");
    }

    @Scheduled(fixedDelay = 5000)
    public void consume() {
        for (String queueName : collectQueueNamesToConsume()) {
            drainQueue(queueName);
        }
    }

    private void drainQueue(String queueName) {
        while (true) {
            String taskId = taskQueue.acquireAndPop(queueName, 1);
            if (taskId == null) {
                return;
            }
            dispatchTask(queueName, taskId);
        }
    }

    private void dispatchTask(String queueName, String taskId) {
        workerExecutor.execute(() -> {
            try {
                taskQueue.markRunning(queueName, taskId, 60);
                processTask(queueName, taskId);
            } catch (Exception e) {
                log.error("[VideoConsumer] 任务处理失败: taskId={}", taskId, e);
            } finally {
                taskQueue.markComplete(queueName, taskId);
                taskQueue.release(queueName);
            }
        });
    }

    private void processTask(String queueName, String taskId) {
        VideoTask task;
        try {
            task = videoGenerationService.getByTaskId(taskId);
        } catch (Exception e) {
            log.error("[VideoConsumer] 任务不存在: taskId={}", taskId);
            return;
        }

        refreshQueueMaxConcurrent(queueName, task.getModelId());
        videoGenerationService.updateStatus(task.getId(), 1, null);

        Map<String, VideoGenerationStrategy> map = getStrategyMap();
        if (map.isEmpty()) {
            videoGenerationService.updateStatus(task.getId(), 3, "没有可用的视频生成策略");
            return;
        }

        // 优先按模型 code 匹配策略名，否则使用第一个策略
        VideoGenerationStrategy strategy = null;
        AiModel model = null;
        if (task.getModelId() != null) {
            try {
                model = aiModelService.getById(task.getModelId());
                if (model != null) {
                    strategy = map.get(model.getCode());
                    if (strategy == null && model.getApiConfigId() != null) {
                        ApiConfig apiConfig = apiConfigService.getById(model.getApiConfigId());
                        if (apiConfig != null) {
                            strategy = map.get(apiConfig.getPlatform());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[VideoConsumer] 模型配置获取失败: modelId={}", task.getModelId());
            }
        }
        if (strategy == null) {
            strategy = map.values().iterator().next();
        }
        try {
            generationModelCapabilityService.validateVideoTask(model, task);
            String platformTaskId = strategy.submit(task);
            log.info("[VideoConsumer] 任务已提交到平台: taskId={}, platformTaskId={}", taskId, platformTaskId);
            strategy.poll(platformTaskId, task);

            // 持久化远程视频文件到本地/OSS 存储
            persistVideoItems(task);

            videoGenerationService.updateStatus(task.getId(), 2, null);
        } catch (Exception e) {
            log.error("[VideoConsumer] 任务执行失败: taskId={}", taskId, e);
            videoGenerationService.updateStatus(task.getId(), 3, e.getMessage());
        }
    }

    private List<String> collectQueueNamesToConsume() {
        List<String> queueNames = new ArrayList<>(taskQueue.listRegisteredQueuesByPrefix(MODEL_QUEUE_PREFIX));
        queueNames.sort(String::compareTo);
        return queueNames;
    }

    private void refreshQueueMaxConcurrent(String queueName, Long modelId) {
        int maxConcurrent = resolveQueueMaxConcurrent(modelId);
        taskQueue.setMaxConcurrent(queueName, maxConcurrent);
    }

    private int resolveQueueMaxConcurrent(Long modelId) {
        AiModel model = resolveQueueModel(modelId);
        Integer configured = model != null ? model.getMaxConcurrency() : null;
        return configured != null && configured > 0 ? configured : 1;
    }

    private AiModel resolveQueueModel(Long modelId) {
        if (modelId != null) {
            try {
                AiModel model = aiModelService.getById(modelId);
                if (model != null && model.getStatus() != null && model.getStatus() == 1) {
                    return model;
                }
            } catch (Exception e) {
                log.warn("[VideoConsumer] 读取视频模型并发配置失败: modelId={}", modelId, e);
            }
        }

        AiModel defaultModel = aiModelService.getDefaultByType(MODEL_TYPE_VIDEO);
        if (defaultModel != null) {
            return defaultModel;
        }

        List<AiModel> videoModels = aiModelService.getListByType(MODEL_TYPE_VIDEO);
        return videoModels.isEmpty() ? null : videoModels.get(0);
    }

    private String resolveQueueName(Long modelId) {
        if (modelId == null) {
            throw new BusinessException("视频生成任务缺少 modelId，无法路由到模型队列");
        }
        return MODEL_QUEUE_PREFIX + modelId;
    }

    @PreDestroy
    public void shutdownWorkerExecutor() {
        workerExecutor.shutdownNow();
    }

    /**
     * 将远程视频/封面 URL 下载到持久化存储（本地磁盘 / S3），
     * 并替换 VideoItem 中的 URL 为永久可访问地址。
     */
    private void persistVideoItems(VideoTask task) {
        List<VideoItem> items = videoGenerationService.listItems(task.getId());
        for (VideoItem item : items) {
            boolean updated = false;

            if (StrUtil.isNotBlank(item.getVideoUrl())) {
                try {
                    String persistedUrl = mediaStorageService.downloadAndStore(item.getVideoUrl(), "videos");
                    item.setVideoUrl(persistedUrl);
                    updated = true;
                    log.info("[VideoConsumer] 视频已持久化: itemId={}", item.getId());
                } catch (Exception e) {
                    log.warn("[VideoConsumer] 视频持久化失败（保留原始 URL）: itemId={}, error={}",
                            item.getId(), e.getMessage());
                }
            }

            if (StrUtil.isNotBlank(item.getCoverUrl())) {
                try {
                    String persistedCoverUrl = mediaStorageService.downloadAndStore(item.getCoverUrl(), "images");
                    item.setCoverUrl(persistedCoverUrl);
                    updated = true;
                } catch (Exception e) {
                    log.warn("[VideoConsumer] 视频封面持久化失败: itemId={}, error={}",
                            item.getId(), e.getMessage());
                }
            }

            if (updated) {
                videoGenerationService.updateItem(item);
            }
        }
    }
}
