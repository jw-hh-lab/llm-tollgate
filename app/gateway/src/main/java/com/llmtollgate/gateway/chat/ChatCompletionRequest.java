package com.llmtollgate.gateway.chat;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * POST /v1/chat/completions 요청 본문. OpenAI 형식과 비슷하게 맞췄다.
 *
 * @param model     "auto"(기본)면 1순위 모델을 쓴다
 * @param maxTokens 최대 출력 토큰. 비우면 기본값(1024)
 * @param stream    true는 4~5주차에 지원한다
 */
public record ChatCompletionRequest(
        String model,
        @NotEmpty @Valid List<Message> messages,
        @Positive Integer maxTokens,
        Boolean stream) {

    public record Message(
            @NotBlank @Pattern(regexp = "system|user|assistant", message = "system, user, assistant 중 하나여야 합니다")
            String role,
            @NotBlank String content) {
    }
}
