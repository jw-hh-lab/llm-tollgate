package com.llmtollgate.gateway.chat;

/**
 * 한 번에 받기 응답. LLM 답 외에 게이트웨이 정보(사용 모델, 토큰, 차감 크레딧)를 함께 돌려준다.
 */
public record ChatCompletionResponse(String id, String content, Gateway gateway) {

    public record Gateway(
            String modelUsed,
            boolean fallbackUsed,
            Tokens tokens,
            long creditsCharged,
            long creditsRemaining,
            boolean cacheHit,
            String finishReason) {
    }

    public record Tokens(long input, long output) {
    }
}
