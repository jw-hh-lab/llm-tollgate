package com.llmtollgate.gateway.chat;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.llmtollgate.gateway.auth.ApiKeyAuthFilter;
import com.llmtollgate.gateway.common.RequestIdFilter;
import com.llmtollgate.gateway.credit.CreditService;

import jakarta.validation.Valid;

@RestController
public class ChatController {

    /** 저장된 응답을 다시 보낼 때 붙이는 헤더. 고객이 "새로 처리된 것인지" 구분할 수 있다 */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IdempotentChatService chatService;
    private final CreditService creditService;

    public ChatController(IdempotentChatService chatService, CreditService creditService) {
        this.chatService = chatService;
        this.creditService = creditService;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> complete(
            @RequestAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTRIBUTE) long customerId,
            @RequestAttribute(RequestIdFilter.ATTRIBUTE) String requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ChatCompletionRequest request) {
        IdempotentChatService.Result result = chatService.handle(customerId, requestId, idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok()
                    .header(REPLAYED_HEADER, "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result.replayedJson());
        }
        return ResponseEntity.ok(result.response());
    }

    @GetMapping("/v1/credits")
    public CreditsResponse credits(@RequestAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTRIBUTE) long customerId) {
        return new CreditsResponse(creditService.balance(customerId));
    }

    public record CreditsResponse(long balance) {
    }
}
