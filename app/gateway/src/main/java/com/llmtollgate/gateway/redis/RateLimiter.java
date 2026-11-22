package com.llmtollgate.gateway.redis;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.llmtollgate.gateway.common.GatewayException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 흐름도 2단계: 고객별 분당·하루 호출 수 제한 (IP 단위 제한은 Nginx가 맡는다).
 *
 * <p>고정 창(분, UTC 날짜) 카운터 두 개를 Lua 스크립트 하나로 올린다. Redis 안에서 한 번에 실행되므로
 * 동시에 요청이 와도 정확히 센다. Redis가 죽어 있으면 제한을 건너뛰고 진행한다(부가 기능 장애가 서비스를 멈추면 안 됨).
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> INCREMENT = RedisScript.of("""
            local m = redis.call('INCR', KEYS[1])
            if m == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            local d = redis.call('INCR', KEYS[2])
            if d == 1 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end
            return {m, d}""", List.class);

    private final StringRedisTemplate redis;
    private final RedisFeatures features;
    private final MeterRegistry meters;

    public RateLimiter(StringRedisTemplate redis, RedisFeatures features, MeterRegistry meters) {
        this.redis = redis;
        this.features = features;
        this.meters = meters;
    }

    /** @throws RateLimitedException 한도를 넘으면 429 */
    public void check(long customerId) {
        if (!features.enabled()) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        long minute = now / 60;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<?> counts;
        try {
            counts = redis.execute(INCREMENT,
                    List.of("rl:" + customerId + ":m:" + minute, "rl:" + customerId + ":d:" + today),
                    "120", "172800");
        } catch (DataAccessException e) {
            meters.counter("tollgate.redis.errors", "feature", "rate_limit").increment();
            log.warn("Redis를 쓸 수 없어 사용량 제한을 건너뜁니다: {}", e.getMessage());
            return;
        }
        long perMinute = ((Number) counts.get(0)).longValue();
        long perDay = ((Number) counts.get(1)).longValue();

        if (perDay > features.rateLimit().perDay()) {
            long untilMidnight = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - now;
            meters.counter("tollgate.ratelimit.rejected", "window", "day").increment();
            throw new RateLimitedException("하루 호출 한도(" + features.rateLimit().perDay() + "회)를 넘었습니다.",
                    untilMidnight);
        }
        if (perMinute > features.rateLimit().perMinute()) {
            meters.counter("tollgate.ratelimit.rejected", "window", "minute").increment();
            throw new RateLimitedException("분당 호출 한도(" + features.rateLimit().perMinute() + "회)를 넘었습니다.",
                    60 - now % 60);
        }
    }

    public static class RateLimitedException extends GatewayException {

        private final long retryAfterSeconds;

        RateLimitedException(String message, long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message);
            this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
