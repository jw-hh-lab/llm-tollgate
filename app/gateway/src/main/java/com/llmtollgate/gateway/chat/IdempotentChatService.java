package com.llmtollgate.gateway.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmtollgate.gateway.idempotency.IdempotencyService;

/**
 * 멱등성 확인을 감싼 채팅 처리. 키가 없으면 그냥 처리하고, 키가 있으면 한 번만 처리한다.
 */
@Service
public class IdempotentChatService {

    private final ChatService chatService;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public IdempotentChatService(ChatService chatService, IdempotencyService idempotency, ObjectMapper mapper) {
        this.chatService = chatService;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /** 새로 처리했으면 response, 이전 결과를 다시 보내면 replayedJson이 채워진다 */
    public record Result(ChatCompletionResponse response, String replayedJson) {
        public boolean replayed() {
            return replayedJson != null;
        }
    }

    public Result handle(long customerId, String requestId, String idempotencyKey, ChatCompletionRequest request) {
        if (idempotencyKey == null) {
            return new Result(chatService.complete(customerId, requestId, request), null);
        }

        IdempotencyService.Outcome outcome =
                idempotency.begin(customerId, idempotencyKey, hash(request), requestId);
        if (outcome instanceof IdempotencyService.Replay replay) {
            return new Result(null, replay.responseBody());
        }

        ChatCompletionResponse response;
        try {
            response = chatService.complete(customerId, requestId, request);
        } catch (RuntimeException e) {
            idempotency.release(customerId, idempotencyKey);
            throw e;
        }
        idempotency.complete(customerId, idempotencyKey, toJson(response));
        return new Result(response, null);
    }

    private String hash(ChatCompletionRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(toJson(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
