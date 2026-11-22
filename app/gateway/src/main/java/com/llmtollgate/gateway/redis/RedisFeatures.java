package com.llmtollgate.gateway.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis를 쓰는 부가 기능(사용량 제한, 응답 캐시) 설정. tollgate.redis.*
 */
@ConfigurationProperties("tollgate.redis")
public record RedisFeatures(boolean enabled, RateLimit rateLimit, Cache cache) {

    public record RateLimit(int perMinute, int perDay) {
    }

    public record Cache(Duration ttl) {
    }
}
