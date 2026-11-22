package com.llmtollgate.gateway.chat;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.llmtollgate.gateway.common.GatewayException;
import com.llmtollgate.gateway.config.GatewayProperties;
import com.llmtollgate.gateway.credit.CreditService;
import com.llmtollgate.gateway.credit.Pricing;
import com.llmtollgate.gateway.llm.LlmClient;
import com.llmtollgate.gateway.llm.LlmException;
import com.llmtollgate.gateway.llm.LlmRequest;
import com.llmtollgate.gateway.llm.LlmResult;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 흐름도 5~7단계: 크레딧 예약 → LLM 호출 → 정산.
 *
 * <p>한 번에 받기({@link #complete})와 스트리밍({@link StreamingChatService})이 같은 예약·정산 규칙을 쓰도록
 * 준비({@link #prepare})와 마무리({@link #settleSuccess}, {@link #refundFailure}, {@link #settlePartial})를 나눠 두었다.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String AUTO = "auto";

    private final List<LlmClient> clients;
    private final CreditService creditService;
    private final Pricing pricing;
    private final LlmCallLogRepository callLogRepository;
    private final GatewayProperties.Llm config;
    private final MeterRegistry meters;

    public ChatService(List<LlmClient> clients, CreditService creditService, Pricing pricing,
                       LlmCallLogRepository callLogRepository, GatewayProperties properties,
                       MeterRegistry meters) {
        this.clients = clients;
        this.creditService = creditService;
        this.pricing = pricing;
        this.callLogRepository = callLogRepository;
        this.config = properties.llm();
        this.meters = meters;
    }

    /** 예약까지 끝낸 호출 준비물 */
    record Prepared(long customerId, String requestId, String model, LlmRequest llmRequest, LlmClient client,
                    long reserved, long startNanos) {

        long elapsedMs() {
            return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        }

        LlmResult stream(Consumer<String> onText) {
            return client.stream(llmRequest, onText);
        }
    }

    /** 한 번에 받기 */
    public ChatCompletionResponse complete(long customerId, String requestId, ChatCompletionRequest request) {
        Prepared p = prepare(customerId, requestId, request);
        StringBuilder content = new StringBuilder();
        LlmResult result;
        try {
            result = p.stream(content::append);
        } catch (LlmException e) {
            // 고객이 아무것도 받지 못했으므로 예약액을 전부 돌려준다
            refundFailure(p, e);
            // (7~8주차) e.reason().retryable()이면 예비 모델(Nova Micro)로 한 번 전환
            throw unavailable();
        } catch (RuntimeException e) {
            creditService.refund(customerId, requestId, p.reserved);
            throw e;
        }
        Settled s = settleSuccess(p, result);
        // (7~8주차) 캐시 저장
        return new ChatCompletionResponse(requestId, content.toString(), gatewayInfo(p, result, s));
    }

    // ---- 스트리밍과 함께 쓰는 단계들 ----

    /** 모델·최대 토큰을 정하고 최대 비용을 예약한다. 잔액이 모자라면 402 */
    Prepared prepare(long customerId, String requestId, ChatCompletionRequest request) {
        // (7~8주차) 사용량 제한, 캐시 조회
        String model = resolveModel(request.model());
        int maxTokens = resolveMaxTokens(request.maxTokens());
        LlmRequest llmRequest = new LlmRequest(model,
                request.messages().stream().map(m -> new LlmRequest.Message(m.role(), m.content())).toList(),
                maxTokens);
        LlmClient client = clientFor(model);
        MDC.put("model", model);

        long reserved = pricing.maxCost(model, llmRequest.totalChars(), maxTokens);
        creditService.reserve(customerId, requestId, reserved);
        return new Prepared(customerId, requestId, model, llmRequest, client, reserved, System.nanoTime());
    }

    record Settled(long charged, long remaining) {
    }

    /** 정상 종료: 실제 사용량으로 정산 */
    Settled settleSuccess(Prepared p, LlmResult result) {
        long latencyMs = p.elapsedMs();
        long cost = pricing.cost(p.model, result.inputTokens(), result.outputTokens());
        long charged = Math.min(cost, p.reserved);
        long remaining = creditService.settle(p.customerId, p.requestId, p.reserved, cost);
        callLogRepository.insert(new LlmCallLogRepository.CallLog(p.requestId, p.customerId, p.model, "SUCCESS",
                null, result.inputTokens(), result.outputTokens(), result.usageEstimated(), p.reserved, charged,
                result.firstTokenMs(), latencyMs));
        Timer.builder("tollgate.llm.latency").tag("model", p.model).tag("outcome", "success")
                .register(meters).record(Duration.ofMillis(latencyMs));
        Timer.builder("tollgate.llm.first_token").tag("model", p.model)
                .register(meters).record(Duration.ofMillis(result.firstTokenMs()));
        meters.counter("tollgate.credits.charged", "model", p.model).increment(charged);
        log.info("LLM 호출 성공 input={} output={} charged={} firstTokenMs={} latencyMs={}",
                result.inputTokens(), result.outputTokens(), charged, result.firstTokenMs(), latencyMs);
        return new Settled(charged, remaining);
    }

    /** LLM 또는 우리 쪽 실패: 전액 환불 */
    Settled refundFailure(Prepared p, LlmException e) {
        long latencyMs = p.elapsedMs();
        long remaining = creditService.refund(p.customerId, p.requestId, p.reserved);
        callLogRepository.insert(new LlmCallLogRepository.CallLog(p.requestId, p.customerId, p.model, "FAILED",
                e.reason().code(), null, null, false, p.reserved, 0, null, latencyMs));
        Timer.builder("tollgate.llm.latency").tag("model", p.model).tag("outcome", "failure")
                .register(meters).record(Duration.ofMillis(latencyMs));
        meters.counter("tollgate.llm.failures", "model", p.model, "reason", e.reason().code()).increment();
        log.warn("LLM 호출 실패 reason={} receivedChars={} latencyMs={}", e.reason(), e.receivedChars(), latencyMs);
        return new Settled(0, remaining);
    }

    /**
     * 고객이 스트림 도중 연결을 끊음: 이미 보낸 만큼만 받는다 (API 명세·차감 규칙 v1).
     * 사용량 정보가 오기 전에 끊겼으므로 입력은 예약 때 쓴 글자 수, 출력은 보낸 글자 수를 토큰 수로 넉넉하게 추정한다.
     */
    Settled settlePartial(Prepared p, long sentChars, long firstTokenMs) {
        long latencyMs = p.elapsedMs();
        long inputTokens = p.llmRequest.totalChars();
        long cost = pricing.cost(p.model, inputTokens, sentChars);
        long charged = Math.min(cost, p.reserved);
        long remaining = creditService.settle(p.customerId, p.requestId, p.reserved, cost);
        callLogRepository.insert(new LlmCallLogRepository.CallLog(p.requestId, p.customerId, p.model,
                "CLIENT_CANCELLED", "client_disconnected", inputTokens, sentChars, true, p.reserved, charged,
                firstTokenMs, latencyMs));
        meters.counter("tollgate.stream.client_cancelled", "model", p.model).increment();
        meters.counter("tollgate.credits.charged", "model", p.model).increment(charged);
        log.info("고객이 스트림을 끊음 sentChars={} charged={} latencyMs={}", sentChars, charged, latencyMs);
        return new Settled(charged, remaining);
    }

    static ChatCompletionResponse.Gateway gatewayInfo(Prepared p, LlmResult result, Settled s) {
        return new ChatCompletionResponse.Gateway(p.model, false,
                new ChatCompletionResponse.Tokens(result.inputTokens(), result.outputTokens()),
                s.charged, s.remaining, false, result.finishReason());
    }

    static GatewayException unavailable() {
        return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "all_models_unavailable",
                "LLM 호출에 실패했습니다. 잠시 후 다시 시도해 주세요. 차감된 크레딧은 없습니다.");
    }

    private String resolveModel(String requested) {
        String model = (requested == null || requested.isBlank() || AUTO.equals(requested))
                ? config.primaryModel()
                : requested;
        if (!pricing.supports(model) || clients.stream().noneMatch(c -> c.supports(model))) {
            throw GatewayException.invalidRequest("지원하지 않는 모델입니다: " + model);
        }
        return model;
    }

    private int resolveMaxTokens(Integer requested) {
        if (requested == null) {
            return config.defaultMaxTokens();
        }
        if (requested > config.maxTokensLimit()) {
            throw GatewayException.invalidRequest("max_tokens는 " + config.maxTokensLimit() + " 이하여야 합니다.");
        }
        return requested;
    }

    private LlmClient clientFor(String model) {
        return clients.stream().filter(c -> c.supports(model)).findFirst().orElseThrow();
    }
}
