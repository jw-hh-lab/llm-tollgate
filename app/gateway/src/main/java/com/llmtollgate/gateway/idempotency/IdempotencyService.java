package com.llmtollgate.gateway.idempotency;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.llmtollgate.gateway.common.GatewayException;

/**
 * 흐름도 3단계: 같은 Idempotency-Key로 온 요청은 한 번만 처리한다.
 *
 * <p>잠금은 (고객, 키) UNIQUE 제약 하나로 한다. 동시에 같은 키로 요청이 오면 INSERT는 하나만 성공하고,
 * 나머지는 "처리 중"(409) 또는 "이미 끝남"(저장된 응답 재전송)으로 갈린다.
 * 성공한 응답만 저장하고, 실패하면 키를 지워서 고객이 같은 키로 다시 시도할 수 있게 한다
 * (실패한 요청은 이미 전액 환불되었으므로 다시 처리해도 이중 청구가 아니다).
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    static final int MAX_KEY_LENGTH = 128;
    /** 이 시간이 지난 키는 없는 것으로 본다 */
    static final Duration RETENTION = Duration.ofHours(24);
    /** 처리 중 표시가 이보다 오래 남아 있으면 서버가 중간에 죽은 것으로 보고 다시 처리한다 (최악의 응답 시간 20초보다 넉넉히) */
    static final Duration STALE_IN_PROGRESS = Duration.ofMinutes(2);

    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public sealed interface Outcome {
    }

    /** 처음 온 요청: 처리를 진행한다 */
    public record Proceed() implements Outcome {
    }

    /** 이미 끝난 요청: 저장된 응답을 그대로 돌려준다 */
    public record Replay(String originalRequestId, String responseBody) implements Outcome {
    }

    public Outcome begin(long customerId, String key, String requestHash, String requestId) {
        validate(key);
        for (int attempt = 0; attempt < 3; attempt++) {
            if (tryInsert(customerId, key, requestHash, requestId)) {
                return new Proceed();
            }
            Optional<Row> existing = find(customerId, key);
            if (existing.isEmpty()) {
                continue; // 그 사이 다른 요청이 지웠다. 다시 시도
            }
            Row row = existing.get();
            LocalDateTime now = LocalDateTime.now();
            boolean expired = row.createdAt.isBefore(now.minus(RETENTION));
            boolean stale = "IN_PROGRESS".equals(row.status) && row.createdAt.isBefore(now.minus(STALE_IN_PROGRESS));
            if (expired || stale) {
                log.info("멱등성 키를 다시 사용합니다 (expired={}, stale={})", expired, stale);
                jdbc.sql("DELETE FROM idempotency_key WHERE id = ?").param(row.id).update();
                continue;
            }
            if (!row.requestHash.equals(requestHash)) {
                throw new GatewayException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused",
                        "같은 Idempotency-Key로 다른 내용의 요청을 보냈습니다. 새 요청에는 새 키를 쓰세요.");
            }
            if ("COMPLETED".equals(row.status)) {
                return new Replay(row.requestId, row.responseBody);
            }
            throw inProgress();
        }
        throw inProgress();
    }

    public void complete(long customerId, String key, String responseBody) {
        jdbc.sql("""
                        UPDATE idempotency_key SET status = 'COMPLETED', response_body = ?
                        WHERE customer_id = ? AND idem_key = ? AND status = 'IN_PROGRESS'""")
                .params(responseBody, customerId, key)
                .update();
    }

    /** 처리에 실패했을 때 키를 풀어 준다 */
    public void release(long customerId, String key) {
        jdbc.sql("DELETE FROM idempotency_key WHERE customer_id = ? AND idem_key = ? AND status = 'IN_PROGRESS'")
                .params(customerId, key)
                .update();
    }

    /** 24시간 지난 키를 한 시간마다 지운다 */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void cleanup() {
        int deleted = jdbc.sql("DELETE FROM idempotency_key WHERE created_at < ?")
                .param(LocalDateTime.now().minus(RETENTION))
                .update();
        if (deleted > 0) {
            log.info("만료된 멱등성 키 {}개 삭제", deleted);
        }
    }

    private boolean tryInsert(long customerId, String key, String requestHash, String requestId) {
        try {
            jdbc.sql("""
                            INSERT INTO idempotency_key (customer_id, idem_key, request_hash, status, request_id, created_at)
                            VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?)""")
                    .params(customerId, key, requestHash, requestId, LocalDateTime.now())
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private Optional<Row> find(long customerId, String key) {
        return jdbc.sql("""
                        SELECT id, request_hash, status, request_id, response_body, created_at
                        FROM idempotency_key WHERE customer_id = ? AND idem_key = ?""")
                .params(customerId, key)
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("request_hash"), rs.getString("status"),
                        rs.getString("request_id"), rs.getString("response_body"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .optional();
    }

    private static void validate(String key) {
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw GatewayException.invalidRequest("Idempotency-Key는 1~" + MAX_KEY_LENGTH + "자여야 합니다.");
        }
    }

    private static GatewayException inProgress() {
        return new GatewayException(HttpStatus.CONFLICT, "idempotency_in_progress",
                "같은 Idempotency-Key의 요청이 아직 처리 중입니다. 잠시 후 같은 키로 다시 보내면 결과를 받을 수 있습니다.");
    }

    private record Row(long id, String requestHash, String status, String requestId, String responseBody,
                       LocalDateTime createdAt) {
    }
}
