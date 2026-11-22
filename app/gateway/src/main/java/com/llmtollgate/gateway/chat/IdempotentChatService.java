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
    private final StreamingChatService streamingService;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public IdempotentChatService(ChatService chatService, StreamingChatService streamingService,
                                 IdempotencyService idempotency, ObjectMapper mapper) {
        this.chatService = chatService;
        this.streamingService = streamingService;
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

    /** 스트리밍: 새로 처리하면 session, 이미 끝난 키면 저장된 응답(JSON, 한 번에)을 replayedJson으로 */
    public record StreamResult(StreamingChatService.StreamSession session, String replayedJson) {
    }

    public StreamResult startStream(long customerId, String requestId, String idempotencyKey,
                                    ChatCompletionRequest request) {
        if (idempotencyKey != null) {
            IdempotencyService.Outcome outcome =
                    idempotency.begin(customerId, idempotencyKey, hash(request), requestId);
            if (outcome instanceof IdempotencyService.Replay replay) {
                return new StreamResult(null, replay.responseBody());
            }
        }
        Runnable release = () -> {
            if (idempotencyKey != null) {
                idempotency.release(customerId, idempotencyKey);
            }
        };
        try {
            StreamingChatService.StreamSession session = streamingService.start(customerId, requestId, request,
                    response -> {
                        if (idempotencyKey != null) {
                            idempotency.complete(customerId, idempotencyKey, toJson(response));
                        }
                    },
                    release); // 중간 실패·고객 이탈이면 키를 풀어 같은 키로 다시 시도할 수 있게
            return new StreamResult(session, null);
        } catch (RuntimeException e) {
            release.run();
            throw e;
        }
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
