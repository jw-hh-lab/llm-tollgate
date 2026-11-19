package com.llmtollgate.gateway.chat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.llmtollgate.gateway.auth.ApiKeyAuthFilter;
import com.llmtollgate.gateway.common.RequestIdFilter;
import com.llmtollgate.gateway.credit.CreditService;

import jakarta.validation.Valid;

@RestController
public class ChatController {

    private final ChatService chatService;
    private final CreditService creditService;

    public ChatController(ChatService chatService, CreditService creditService) {
        this.chatService = chatService;
        this.creditService = creditService;
    }

    @PostMapping("/v1/chat/completions")
    public ChatCompletionResponse complete(
            @RequestAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTRIBUTE) long customerId,
            @RequestAttribute(RequestIdFilter.ATTRIBUTE) String requestId,
            @Valid @RequestBody ChatCompletionRequest request) {
        return chatService.complete(customerId, requestId, request);
    }

    @GetMapping("/v1/credits")
    public CreditsResponse credits(@RequestAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTRIBUTE) long customerId) {
        return new CreditsResponse(creditService.balance(customerId));
    }

    public record CreditsResponse(long balance) {
    }
}
