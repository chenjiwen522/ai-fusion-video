package com.stonewu.fusion.service.generation.strategy.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.proxy.AiProxySupport;
import com.stonewu.fusion.service.generation.VideoGenerationService;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容（new-api 协议）视频生成策略
 * <p>
 * 适用于一切走 new-api / OpenAI 兼容协议的视频网关：
 * 星光API（xgapi.top）、云雾 API、其他第三方 OpenAI 兼容聚合网关。
 * <p>
 * 协议：
 * <ul>
 *   <li>提交任务：POST {base}/v1/videos</li>
 *   <li>查询任务：GET  {base}/v1/videos/{task_id}</li>
 * </ul>
 * <p>
 * 请求体的关键字段（new-api 约定）：
 * <ul>
 *   <li>{@code model} — 模型 code</li>
 *   <li>{@code prompt} — 提示词</li>
 *   <li>{@code seconds} — 时长，<b>字符串</b>类型，如 "6" / "10"</li>
 *   <li>{@code size} — 尺寸，比例字符串如 "16:9" / "9:16" / "1:1"，或绝对尺寸如 "1280x720"</li>
 *   <li>{@code image_url} — 首帧图（图生视频）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiCompatibleVideoStrategy implements VideoGenerationStrategy {

    /** 平台名称 */
    public static final String PLATFORM_NAME = "openai_compatible";

    /** 默认视频模型 */
    private static final String DEFAULT_MODEL_ID = "sora-2";

    /** 轮询间隔（秒） */
    private static final int POLL_INTERVAL_SECONDS = 10;

    /** 默认轮询超时（秒），60 分钟 */
    private static final int DEFAULT_POLL_TIMEOUT_SECONDS = 3600;

    /** 最大轮询次数 */
    private static final int MAX_POLL_COUNT = DEFAULT_POLL_TIMEOUT_SECONDS / POLL_INTERVAL_SECONDS;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

    /** 错误日志前缀 */
    protected static final String LOG_PREFIX = "[Video/OpenAI兼容]";

    /** 错误消息前缀 */
    protected static final String ERR_PREFIX = "OpenAI 兼容视频网关";

    private final AiModelService aiModelService;
    private final ApiConfigService apiConfigService;
    private final VideoGenerationService videoGenerationService;

    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    public String getName() {
        return PLATFORM_NAME;
    }

    // ========================================================================
    // submit
    // ========================================================================

    @Override
    public String submit(VideoTask task) {
        AiModel model = resolveModel(task);
        ApiConfig apiConfig = resolveApiConfig(model);
        String modelCode = resolveModelCode(model);

        String url = buildUrl(apiConfig, "/videos");
        JSONObject body = new JSONObject();
        body.set("model", modelCode);
        body.set("prompt", StrUtil.nullToDefault(task.getPrompt(), ""));

        // 时长 —— new-api 约定：seconds 是字符串
        // 按模型 config 中的 min/max/defaultDuration 自动 clamp，
        // 避免 AI Agent 传 duration=5 给只支持 10 秒的模型这种问题
        Integer duration = clampDurationByModelConfig(task.getDuration(), model);
        if (duration != null && duration > 0) {
            body.set("seconds", String.valueOf(duration));
            // 兼容字段（部分网关可能用 duration）
            body.set("duration", duration);
        }

        // 尺寸 —— size 字段是首选，可传比例或 WxH
        String sizeValue = firstNonBlankStr(task.getResolution(), task.getRatio());
        if (StrUtil.isNotBlank(sizeValue)) {
            body.set("size", sizeValue);
            // 兼容字段
            if (StrUtil.isNotBlank(task.getRatio())) {
                body.set("aspect_ratio", task.getRatio());
            }
        }

        // 首帧（图生视频）
        if (StrUtil.isNotBlank(task.getFirstFrameImageUrl())) {
            body.set("image_url", task.getFirstFrameImageUrl());
            body.set("image", task.getFirstFrameImageUrl());
        }

        // 尾帧
        if (StrUtil.isNotBlank(task.getLastFrameImageUrl())) {
            body.set("last_frame_image", task.getLastFrameImageUrl());
        }

        // 参考图列表
        List<String> refImageUrls = parseJsonUrls(task.getReferenceImageUrls());
        if (refImageUrls != null && !refImageUrls.isEmpty()) {
            body.set("reference_images", refImageUrls);
        }

        // 种子
        if (task.getSeed() != null) {
            body.set("seed", task.getSeed());
        }

        // 数量
        body.set("n", task.getCount() != null && task.getCount() > 0 ? task.getCount() : 1);

        // 水印 / 音频
        if (Boolean.TRUE.equals(task.getWatermark())) {
            body.set("watermark", true);
        }
        if (Boolean.TRUE.equals(task.getGenerateAudio())) {
            body.set("generate_audio", true);
            body.set("with_audio", true);
        }

        log.info("{} 创建视频生成任务: model={}, mode={}, size={}, seconds={}",
                LOG_PREFIX, modelCode, task.getGenerateMode(), sizeValue, task.getDuration());

        String resp = postJson(url, body.toString(), apiConfig);
        log.debug("{} submit response: {}", LOG_PREFIX, truncate(resp));

        JSONObject json = parseJsonOrThrow(resp);
        ensureNoError(json);

        String taskId = firstNonBlank(
                json.getStr("id"),
                json.getStr("task_id"),
                getNested(json, "data", "id"),
                getNested(json, "data", "task_id"));

        if (StrUtil.isBlank(taskId)) {
            String directUrl = extractVideoUrl(json);
            if (StrUtil.isNotBlank(directUrl)) {
                String fakeId = "sync:" + directUrl;
                log.info("{} 同步返回视频 URL: {}", LOG_PREFIX, directUrl);
                return fakeId;
            }
            throw new RuntimeException(ERR_PREFIX + " 视频提交响应缺少 task id，原始响应：" + truncate(resp));
        }

        log.info("{} 任务已创建: platformTaskId={}", LOG_PREFIX, taskId);
        return taskId;
    }

    // ========================================================================
    // poll
    // ========================================================================

    @Override
    public void poll(String platformTaskId, VideoTask task) {
        if (platformTaskId != null && platformTaskId.startsWith("sync:")) {
            String directUrl = platformTaskId.substring("sync:".length());
            persistResult(task, directUrl, null, null);
            return;
        }

        AiModel model = resolveModel(task);
        ApiConfig apiConfig = resolveApiConfig(model);
        String url = buildUrl(apiConfig, "/videos/" + platformTaskId);

        log.info("{} 开始轮询任务状态: platformTaskId={}", LOG_PREFIX, platformTaskId);

        for (int i = 0; i < MAX_POLL_COUNT; i++) {
            String resp = getJson(url, apiConfig);
            log.debug("{} poll {}: {}", LOG_PREFIX, i + 1, truncate(resp));

            JSONObject json = parseJsonOrThrow(resp);
            ensureNoError(json);

            String status = firstNonBlank(
                    json.getStr("status"),
                    getNested(json, "data", "status"));
            if (StrUtil.isBlank(status)) status = "processing";

            String lower = status.toLowerCase();

            if (isSucceeded(lower)) {
                log.info("{} 任务成功: platformTaskId={}", LOG_PREFIX, platformTaskId);
                String videoUrl = extractVideoUrl(json);
                if (StrUtil.isBlank(videoUrl)) {
                    throw new RuntimeException(ERR_PREFIX + " 任务成功但无视频 URL，原始响应：" + truncate(resp));
                }
                String coverUrl = firstNonBlank(
                        json.getStr("cover_url"),
                        json.getStr("thumbnail"),
                        getNested(json, "data", "cover_url"));
                Integer duration = parseInt(firstNonBlank(
                        json.getStr("duration"),
                        getNested(json, "data", "duration")));
                persistResult(task, videoUrl, coverUrl, duration);
                return;
            }

            if (isFailed(lower)) {
                String err = firstNonBlank(
                        json.getStr("error_message"),
                        json.getStr("message"),
                        getNested(json, "error", "message"),
                        getNested(json, "data", "error_message"),
                        "未知错误");
                throw new RuntimeException(ERR_PREFIX + " 视频生成失败: " + err);
            }

            log.debug("{} 任务进行中: platformTaskId={}, status={}, pollCount={}/{}",
                    LOG_PREFIX, platformTaskId, status, i + 1, MAX_POLL_COUNT);
            sleep(POLL_INTERVAL_SECONDS);
        }

        throw new RuntimeException(ERR_PREFIX + " 视频生成超时（轮询 " + MAX_POLL_COUNT + " 次）");
    }

    // ========================================================================
    // helpers
    // ========================================================================

    private void persistResult(VideoTask task, String videoUrl, String coverUrl, Integer duration) {
        List<VideoItem> items = videoGenerationService.listItems(task.getId());
        if (!items.isEmpty()) {
            VideoItem item = items.get(0);
            item.setVideoUrl(videoUrl);
            if (StrUtil.isNotBlank(coverUrl)) item.setCoverUrl(coverUrl);
            if (duration != null && duration > 0) item.setDuration(duration);
            item.setStatus(1);
            videoGenerationService.updateItem(item);
        }
        task.setSuccessCount(1);
        videoGenerationService.update(task);
        log.info("{} 视频生成完成: taskId={}, videoUrl={}", LOG_PREFIX, task.getTaskId(), videoUrl);
    }

    private String extractVideoUrl(JSONObject json) {
        String top = firstNonBlank(json.getStr("video_url"), json.getStr("url"));
        if (StrUtil.isNotBlank(top)) return top;

        Object dataObj = json.getObj("data");
        if (dataObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) dataObj;
            if (!arr.isEmpty() && arr.get(0) instanceof JSONObject) {
                JSONObject first = (JSONObject) arr.get(0);
                String u = firstNonBlank(first.getStr("video_url"), first.getStr("url"));
                if (StrUtil.isNotBlank(u)) return u;
            }
        } else if (dataObj instanceof JSONObject) {
            JSONObject d = (JSONObject) dataObj;
            String u = firstNonBlank(d.getStr("video_url"), d.getStr("url"));
            if (StrUtil.isNotBlank(u)) return u;
        }

        for (String key : new String[]{"output", "result", "response"}) {
            Object obj = json.getObj(key);
            if (obj instanceof JSONObject) {
                JSONObject o = (JSONObject) obj;
                String u = firstNonBlank(o.getStr("video_url"), o.getStr("url"));
                if (StrUtil.isNotBlank(u)) return u;
            }
        }
        return null;
    }

    private boolean isSucceeded(String status) {
        return "succeeded".equals(status) || "success".equals(status)
                || "completed".equals(status) || "complete".equals(status)
                || "done".equals(status) || "finished".equals(status);
    }

    private boolean isFailed(String status) {
        return "failed".equals(status) || "fail".equals(status)
                || "error".equals(status) || "cancelled".equals(status)
                || "canceled".equals(status) || "rejected".equals(status);
    }

    private String buildUrl(ApiConfig apiConfig, String path) {
        String base = apiConfig.getApiUrl();
        if (StrUtil.isBlank(base)) {
            base = "https://api.openai.com";
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String lower = base.toLowerCase();
        if (!lower.endsWith("/v1") && !lower.contains("/v1/")) {
            base = base + "/v1";
        }
        return base + path;
    }

    private String postJson(String url, String json, ApiConfig apiConfig) {
        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        Request.Builder req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json");
        if (StrUtil.isNotBlank(apiConfig.getApiKey())) {
            req.header("Authorization", "Bearer " + apiConfig.getApiKey());
        }
        try (Response resp = client.newCall(req.build()).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new RuntimeException(ERR_PREFIX + " 请求失败 " + resp.code() + ": " + truncate(body));
            }
            return body;
        } catch (IOException e) {
            throw new RuntimeException(ERR_PREFIX + " 请求 IO 异常: " + e.getMessage(), e);
        }
    }

    private String getJson(String url, ApiConfig apiConfig) {
        OkHttpClient client = AiProxySupport.okHttpClient(okHttpClient, apiConfig);
        Request.Builder req = new Request.Builder().url(url).get();
        if (StrUtil.isNotBlank(apiConfig.getApiKey())) {
            req.header("Authorization", "Bearer " + apiConfig.getApiKey());
        }
        try (Response resp = client.newCall(req.build()).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new RuntimeException(ERR_PREFIX + " 查询失败 " + resp.code() + ": " + truncate(body));
            }
            return body;
        } catch (IOException e) {
            throw new RuntimeException(ERR_PREFIX + " 查询 IO 异常: " + e.getMessage(), e);
        }
    }

    private JSONObject parseJsonOrThrow(String resp) {
        try {
            return JSONUtil.parseObj(resp);
        } catch (Exception e) {
            throw new RuntimeException(ERR_PREFIX + " 响应不是合法 JSON: " + truncate(resp));
        }
    }

    private void ensureNoError(JSONObject json) {
        Object errObj = json.getObj("error");
        if (errObj instanceof JSONObject) {
            String msg = ((JSONObject) errObj).getStr("message");
            if (StrUtil.isNotBlank(msg)) {
                throw new RuntimeException(ERR_PREFIX + " 错误: " + msg);
            }
        }
    }

    /**
     * 根据模型 config 中的 minDuration/maxDuration/defaultDuration 修正时长。
     * <p>
     * 解决 AI Agent 默认传 duration=5 给只支持 10 秒的模型（如 grok-video-3-10s）的问题。
     * 优先级：
     *   1) 入参在 [min, max] 范围内 → 原样返回
     *   2) 入参超出范围 → 用 defaultDuration（如有）；否则 clamp 到 [min, max]
     *   3) 入参为空 → 用 defaultDuration / minDuration
     */
    private Integer clampDurationByModelConfig(Integer requestedDuration, AiModel model) {
        if (model == null || StrUtil.isBlank(model.getConfig())) {
            return requestedDuration;
        }
        try {
            JSONObject config = JSONUtil.parseObj(model.getConfig());
            Integer min = config.getInt("minDuration");
            Integer max = config.getInt("maxDuration");
            Integer def = config.getInt("defaultDuration");

            if (requestedDuration == null || requestedDuration <= 0) {
                return def != null ? def : (min != null ? min : null);
            }
            boolean tooLow = (min != null && requestedDuration < min);
            boolean tooHigh = (max != null && requestedDuration > max);
            if (tooLow || tooHigh) {
                Integer fixed = def != null ? def
                        : (tooLow ? min : max);
                log.warn("{} 时长 {}s 超出模型 [{}~{}]，自动改为 {}s（model={}）",
                        LOG_PREFIX, requestedDuration, min, max, fixed, model.getCode());
                return fixed;
            }
            return requestedDuration;
        } catch (Exception e) {
            // config 解析失败，按原值返回
            return requestedDuration;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (StrUtil.isNotBlank(v)) return v;
        }
        return null;
    }

    private String firstNonBlankStr(String... values) {
        return firstNonBlank(values);
    }

    private String getNested(JSONObject root, String key1, String key2) {
        Object obj = root.getObj(key1);
        if (obj instanceof JSONObject) {
            return ((JSONObject) obj).getStr(key2);
        }
        return null;
    }

    private Integer parseInt(String s) {
        if (StrUtil.isBlank(s)) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            try {
                return (int) Math.round(Double.parseDouble(s));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private void sleep(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("视频生成轮询被中断", e);
        }
    }

    private List<String> parseJsonUrls(String jsonUrls) {
        if (StrUtil.isBlank(jsonUrls)) return null;
        try {
            JSONArray arr = JSONUtil.parseArray(jsonUrls);
            List<String> urls = arr.toList(String.class);
            return urls.isEmpty() ? null : urls;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveModelCode(AiModel model) {
        if (model != null && StrUtil.isNotBlank(model.getCode())) {
            return model.getCode();
        }
        return DEFAULT_MODEL_ID;
    }

    private AiModel resolveModel(VideoTask task) {
        if (task.getModelId() != null) {
            try {
                return aiModelService.getById(task.getModelId());
            } catch (Exception e) {
                log.warn("{} 获取模型失败: modelId={}", LOG_PREFIX, task.getModelId());
            }
        }
        return null;
    }

    private ApiConfig resolveApiConfig(AiModel model) {
        if (model != null && model.getApiConfigId() != null) {
            try {
                ApiConfig config = apiConfigService.getById(model.getApiConfigId());
                if (config != null) return config;
            } catch (Exception e) {
                log.warn("{} 获取 API 配置失败: apiConfigId={}", LOG_PREFIX, model.getApiConfigId());
            }
        }
        throw new RuntimeException("未找到 OpenAI 兼容视频生成 API 配置，请在系统设置中配置");
    }
}
