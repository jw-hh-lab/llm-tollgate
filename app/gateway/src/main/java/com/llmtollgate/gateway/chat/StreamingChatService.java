package com.llmtollgate.gateway.chat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmtollgate.gateway.config.GatewayProperties;
import com.llmtollgate.gateway.credit.CreditService;
import com.llmtollgate.gateway.llm.LlmException;
import com.llmtollgate.gateway.llm.LlmResult;

/**
 * 스트리밍(SSE) 응답.
 *
 * <p>규칙 (11/9 회의, API 명세·차감 규칙 v1):
 * <ul>
 *   <li>첫 글자가 오기 전에는 응답 헤더를 보내지 않는다. 그래서 그 전에 실패하면 평범한 503 JSON으로 알릴 수 있다</li>
 *   <li>첫 글자 뒤의 실패는 상태 코드를 바꿀 수 없으므로 {@code event: error}로 알린다</li>
 *   <li>정상 종료는 {@code done} 이벤트에 사용량과 차감액을 담는다</li>
 *   <li>LLM이나 우리 쪽이 끊으면 전액 환불, 고객이 끊으면 보낸 만큼만 받는다</li>
 * </ul>
 *
 * <p>LLM 호출은 별도 가상 스레드가 하고, 조각을 큐로 넘긴다. 고객에게 쓰는 쪽은 요청을 받은 스레드다.
 * 고객이 끊으면 쓰기에서 IOException이 나고, 그때 LLM 쪽에 중단 신호를 보낸다.
 */
@Service
public class StreamingChatService {

    private static final Logger log = LoggerFactory.getLogger(StreamingChatService.class);

    private final ChatService chatService;
    private final CreditService creditService;
    private final ObjectMapper mapper;
    private final Duration firstEventLimit;

    public StreamingChatService(ChatService chatService, CreditService creditService, ObjectMapper mapper,
                                GatewayProperties properties) {
        this.chatService = chatService;
        this.creditService = creditService;
        this.mapper = mapper;
        // LLM 클라이언트가 첫 토큰 시간 초과를 직접 지키므로, 이 값은 만일을 위한 안전장치다
        this.firstEventLimit = properties.llm().firstTokenTimeout().plusSeconds(5);
    }

    // ---- 생산자(LLM) → 소비자(고객) 사이의 이벤트 ----

    sealed interface Event {
    }

    record Text(String text) implements Event {
    }

    record Done(LlmResult result) implements Event {
    }

    record Failed(LlmException error) implements Event {
    }

    record Crashed(RuntimeException error) implements Event {
    }

    /** 고객이 끊어서 LLM 읽기를 멈추라는 신호 */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super(null, null, false, false);
        }
    }

    /**
     * 예약하고 LLM 호출을 시작한 뒤, 첫 이벤트가 올 때까지 기다린다.
     * 첫 이벤트가 실패면 전액 환불하고 503을 던진다 (아직 헤더를 보내지 않았으므로 가능).
     */
    public StreamSession start(long customerId, String requestId, ChatCompletionRequest request,
                               Consumer<ChatCompletionResponse> onSuccess, Runnable onFailure) {
        ChatService.Prepared p = chatService.prepare(customerId, requestId, request);
        BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
        AtomicBoolean cancelled = new AtomicBoolean();

        Thread.ofVirtual().name("llm-stream-" + requestId).start(() -> {
            try {
                LlmResult result = p.stream(text -> {
                    if (cancelled.get()) {
                        throw new Cancelled();
                    }
                    queue.add(new Text(text));
                });
                queue.add(new Done(result));
            } catch (Cancelled c) {
                // 고객이 떠났다. 정산은 쓰는 쪽에서 이미 했다
            } catch (LlmException e) {
                queue.add(new Failed(e));
            } catch (RuntimeException e) {
                queue.add(new Crashed(e));
            }
        });

        Event first;
        try {
            first = queue.poll(firstEventLimit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            first = null;
        }
        if (first == null) {
            cancelled.set(true);
            first = new Failed(new LlmException(LlmException.Reason.FIRST_TOKEN_TIMEOUT, "첫 이벤트 대기 시간 초과"));
        }
        if (first instanceof Failed f) {
            chatService.refundFailure(p, f.error());
            throw ChatService.unavailable();
        }
        if (first instanceof Crashed c) {
            creditService.refund(customerId, requestId, p.reserved());
            throw c.error();
        }
        return new StreamSession(p, queue, cancelled, first, onSuccess, onFailure);
    }

    /** 첫 이벤트를 받은 스트림. {@link #writeTo}가 고객에게 끝까지 흘려보내고 정산까지 마친다 */
    public final class StreamSession {

        private final ChatService.Prepared p;
        private final BlockingQueue<Event> queue;
        private final AtomicBoolean cancelled;
        private final Event first;
        private final Consumer<ChatCompletionResponse> onSuccess;
        private final Runnable onFailure;
        private final long firstTokenMs;

        private StreamSession(ChatService.Prepared p, BlockingQueue<Event> queue, AtomicBoolean cancelled,
                              Event first, Consumer<ChatCompletionResponse> onSuccess, Runnable onFailure) {
            this.p = p;
            this.queue = queue;
            this.cancelled = cancelled;
            this.first = first;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
            this.firstTokenMs = p.elapsedMs();
        }

        public void writeTo(OutputStream out) {
            StringBuilder content = new StringBuilder();
            long sentChars = 0;
            boolean finished = false; // 정산·환불을 이미 했는지 (두 번 하지 않게)
            Event event = first;
            try {
                while (true) {
                    if (event instanceof Text t) {
                        write(out, null, new DeltaEvent(t.text()));
                        sentChars += t.text().length();
                        content.append(t.text());
                    } else if (event instanceof Done d) {
                        ChatService.Settled s = chatService.settleSuccess(p, d.result());
                        finished = true;
                        ChatCompletionResponse.Gateway info = ChatService.gatewayInfo(p, d.result(), s);
                        onSuccess.accept(new ChatCompletionResponse(p.requestId(), content.toString(), info));
                        write(out, null, new DoneEvent(true, p.requestId(), info.modelUsed(), info.tokens(),
                                info.creditsCharged(), info.creditsRemaining(), info.finishReason()));
                        return;
                    } else if (event instanceof Failed f) {
                        ChatService.Settled s = chatService.refundFailure(p, f.error());
                        finished = true;
                        onFailure.run();
                        write(out, "error", new ErrorEvent(errorCode(f.error()),
                                "LLM 응답이 중간에 끊겼습니다. 차감된 크레딧은 없습니다.", 0, s.remaining()));
                        return;
                    } else if (event instanceof Crashed c) {
                        long remaining = creditService.refund(p.customerId(), p.requestId(), p.reserved());
                        finished = true;
                        onFailure.run();
                        log.error("스트리밍 중 처리하지 못한 오류", c.error());
                        write(out, "error", new ErrorEvent("internal_error",
                                "서버 내부 오류가 발생했습니다. 차감된 크레딧은 없습니다.", 0, remaining));
                        return;
                    }
                    event = next();
                }
            } catch (IOException clientGone) {
                cancelled.set(true);
                if (!finished) {
                    chatService.settlePartial(p, sentChars, firstTokenMs);
                    onFailure.run();
                }
            }
        }

        private Event next() {
            try {
                // 생산자는 조각 사이 시간 초과를 스스로 지키므로 결국 이벤트를 넣는다. 그래도 무한 대기는 피한다
                Event e = queue.poll(5, TimeUnit.MINUTES);
                return e != null ? e
                        : new Failed(new LlmException(LlmException.Reason.IDLE_TIMEOUT, "스트림 대기 시간 초과"));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new Failed(new LlmException(LlmException.Reason.CONNECTION_FAILED, "중단됨"));
            }
        }

        private void write(OutputStream out, String eventName, Object payload) throws IOException {
            StringBuilder sb = new StringBuilder();
            if (eventName != null) {
                sb.append("event: ").append(eventName).append('\n');
            }
            sb.append("data: ").append(mapper.writeValueAsString(payload)).append("\n\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush(); // 조각마다 바로 내보낸다
        }
    }

    private static String errorCode(LlmException e) {
        return switch (e.reason()) {
            case IDLE_TIMEOUT -> "upstream_stalled";
            case FIRST_TOKEN_TIMEOUT -> "upstream_timeout";
            default -> "upstream_failed";
        };
    }

    record DeltaEvent(String delta) {
    }

    record DoneEvent(boolean done, String id, String modelUsed, ChatCompletionResponse.Tokens tokens,
                     long creditsCharged, long creditsRemaining, String finishReason) {
    }

    record ErrorEvent(String code, String message, long creditsCharged, long creditsRemaining) {
    }
}
