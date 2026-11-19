package com.llmtollgate.gateway.llm;

import java.util.List;

/**
 * LLM 공급자와 무관한 호출 요청.
 *
 * @param role system, user, assistant 중 하나
 */
public record LlmRequest(String model, List<Message> messages, int maxOutputTokens) {

    public record Message(String role, String content) {
    }

    public long totalChars() {
        return messages.stream().mapToLong(m -> m.content().length()).sum();
    }
}
