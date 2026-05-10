package com.stonewu.fusion.service.generation.strategy.impl;

import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 云雾 API 视频生成策略 —— 别名 bean。
 * <p>
 * 云雾 API 走 new-api 标准协议，与 {@link OpenAiCompatibleVideoStrategy} 完全一致。
 * 此 bean 只是为了在前端 platform 字段为 {@code "yunwu"} 时也能正确路由到统一实现。
 */
@Component
@RequiredArgsConstructor
public class YunwuVideoStrategy implements VideoGenerationStrategy {

    public static final String PLATFORM_NAME = "yunwu";

    private final OpenAiCompatibleVideoStrategy delegate;

    @Override
    public String getName() {
        return PLATFORM_NAME;
    }

    @Override
    public String submit(VideoTask task) {
        return delegate.submit(task);
    }

    @Override
    public void poll(String platformTaskId, VideoTask task) {
        delegate.poll(platformTaskId, task);
    }
}
