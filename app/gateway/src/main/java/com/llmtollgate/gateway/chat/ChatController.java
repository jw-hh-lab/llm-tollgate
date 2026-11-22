package com.llmtollgate.gateway.chat;

import java.io.IOException;

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

import jakarta.servlet.http.HttpServletResponse;
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
            @Valid @RequestBody ChatCompletionRequest request,
            HttpServletResponse servletResponse) throws IOException {
        if (Boolean.TRUE.equals(request.stream())) {
            return stream(customerId, requestId, idempotencyKey, request, servletResponse);
        }
        IdempotentChatService.Result result = chatService.handle(customerId, requestId, idempotencyKey, request);
        if (result.replayed()) {
            return replay(result.replayedJson());
        }
        return ResponseEntity.ok(result.response());
    }

    /**
     * 스트리밍. 첫 글자를 받기 전에 실패하면 예외(503 등)가 그대로 JSON 오류로 나간다.
     * 첫 글자를 받은 뒤에야 헤더를 보내고 SSE를 흘려보낸다.
     *
     * <p>요청을 받은 스레드가 스트림이 끝날 때까지 직접 쓴다. 동시 스트림 수만큼 스레드가 묶이므로,
     * 가상 스레드(SPRING_THREADS_VIRTUAL_ENABLED=true)를 켜기 전후를 부하 테스트로 비교한다.
     */
    private ResponseEntity<?> stream(long customerId, String requestId, String idempotencyKey,
                                     ChatCompletionRequest request, HttpServletResponse servletResponse)
            throws IOException {
        IdempotentChatService.StreamResult result =
                chatService.startStream(customerId, requestId, idempotencyKey, request);
        if (result.replayedJson() != null) {
            return replay(result.replayedJson()); // 이미 끝난 요청은 한 번에 받기 형식으로 돌려준다
        }
        servletResponse.setStatus(200);
        servletResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        servletResponse.setCharacterEncoding("UTF-8");
        servletResponse.setHeader("Cache-Control", "no-cache");
        servletResponse.setHeader("X-Accel-Buffering", "no"); // Nginx에게 이 응답은 모으지 말라고 알림
        result.session().writeTo(servletResponse.getOutputStream());
        return null; // 이미 직접 썼다
    }

    private static ResponseEntity<String> replay(String json) {
        return ResponseEntity.ok()
                .header(REPLAYED_HEADER, "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @GetMapping("/v1/credits")
    public CreditsResponse credits(@RequestAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTRIBUTE) long customerId) {
        return new CreditsResponse(creditService.balance(customerId));
    }

    public record CreditsResponse(long balance) {
    }
}
