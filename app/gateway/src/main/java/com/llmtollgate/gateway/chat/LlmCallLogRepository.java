package com.llmtollgate.gateway.chat;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LlmCallLogRepository {

    private final JdbcClient jdbc;

    public LlmCallLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CallLog log) {
        jdbc.sql("""
                        INSERT INTO llm_call_log (request_id, customer_id, model, status, error_code,
                            input_tokens, output_tokens, usage_estimated, credits_reserved, credits_charged,
                            first_token_ms, latency_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(log.requestId(), log.customerId(), log.model(), log.status(), log.errorCode(),
                        log.inputTokens(), log.outputTokens(), log.usageEstimated(), log.creditsReserved(),
                        log.creditsCharged(), log.firstTokenMs(), log.latencyMs())
                .update();
    }

    public record CallLog(
            String requestId,
            long customerId,
            String model,
            String status,
            String errorCode,
            Long inputTokens,
            Long outputTokens,
            boolean usageEstimated,
            long creditsReserved,
            long creditsCharged,
            Long firstTokenMs,
            long latencyMs) {
    }
}
