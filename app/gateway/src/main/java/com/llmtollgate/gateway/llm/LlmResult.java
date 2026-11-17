package com.llmtollgate.gateway.llm;

/**
 * 스트림을 끝까지 받은 결과.
 *
 * @param usageEstimated 공급자가 사용량을 보내 주지 않아 글자 수로 추정했으면 true
 * @param firstTokenMs   요청을 보낸 뒤 첫 글자가 올 때까지 걸린 시간
 */
public record LlmResult(
        String model,
        long inputTokens,
        long outputTokens,
        boolean usageEstimated,
        String finishReason,
        long firstTokenMs) {
}
