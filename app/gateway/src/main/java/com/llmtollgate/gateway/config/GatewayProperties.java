package com.llmtollgate.gateway.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 tollgate.* 설정.
 */
@ConfigurationProperties("tollgate")
public record GatewayProperties(String adminToken, Llm llm, Pricing pricing) {

    public record Llm(
            String primaryModel,
            Duration connectTimeout,
            Duration firstTokenTimeout,
            Duration idleTimeout,
            int defaultMaxTokens,
            int maxTokensLimit,
            Gemini gemini) {
    }

    public record Gemini(String baseUrl, String apiKey, Integer thinkingBudget) {
    }

    public record Pricing(Map<String, ModelPrice> models) {
    }

    /** 100만 토큰당 크레딧 */
    public record ModelPrice(long inputPerMillion, long outputPerMillion) {
    }
}
