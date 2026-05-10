package com.stonewu.fusion.service.generation.strategy.impl;

import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.generation.strategy.VideoGenerationStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 星光API（xgapi.top）视频生成策略 —— 别名 bean。
 * <p>
 * 协议与 {@link OpenAiCompatibleVideoStrategy} 完全一致（均为 new-api 标准协议）。
 * 此 bean 只是为了在前端 platform 字段为 {@code "xgapi"} 时也能正确路由。
 */
@Component
@RequiredArgsConstructor
public class XgApiVideoStrategy implements VideoGenerationStrategy {

    public static final String PLATFORM_NAME = "xgapi";

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
