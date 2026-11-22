package com.llmtollgate.gateway.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmtollgate.gateway.llm.LlmRequest;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 흐름도 4단계: 같은 질문이면 저장된 답을 돌려준다 (LLM 비용 0, 크레딧 차감 0).
 *
 * <p>키는 고객 + 모델 + 최대 토큰 + 메시지 전체의 해시다. 고객마다 따로 둬서, 다른 고객이 같은 질문을 했는지가
 * 응답 속도로 드러나지 않게 했다. 스트리밍 여부는 키에 넣지 않아 한 번에 받기로 저장한 답을 스트림으로도 돌려준다.
 * Redis가 죽어 있으면 캐시를 건너뛴다.
 */
@Component
public class ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(ResponseCache.class);

    private final StringRedisTemplate redis;
    private final RedisFeatures features;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public ResponseCache(StringRedisTemplate redis, RedisFeatures features, ObjectMapper mapper,
                         MeterRegistry meters) {
        this.redis = redis;
        this.features = features;
        this.mapper = mapper;
        this.meters = meters;
    }

    public record CachedAnswer(String content, long inputTokens, long outputTokens, String finishReason) {
    }

    public Optional<CachedAnswer> get(long customerId, LlmRequest request) {
        if (!features.enabled()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key(customerId, request));
            if (json == null) {
                meters.counter("tollgate.cache.requests", "result", "miss").increment();
                return Optional.empty();
            }
            meters.counter("tollgate.cache.requests", "result", "hit").increment();
            return Optional.of(mapper.readValue(json, CachedAnswer.class));
        } catch (DataAccessException | JsonProcessingException e) {
            meters.counter("tollgate.redis.errors", "feature", "cache").increment();
            log.warn("캐시를 읽지 못해 건너뜁니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(long customerId, LlmRequest request, CachedAnswer answer) {
        if (!features.enabled() || answer.content().isEmpty()) {
            return;
        }
        try {
            redis.opsForValue().set(key(customerId, request), mapper.writeValueAsString(answer),
                    features.cache().ttl());
        } catch (DataAccessException | JsonProcessingException e) {
            meters.counter("tollgate.redis.errors", "feature", "cache").increment();
            log.warn("캐시에 저장하지 못했습니다: {}", e.getMessage());
        }
    }

    private String key(long customerId, LlmRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return "cache:" + customerId + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
