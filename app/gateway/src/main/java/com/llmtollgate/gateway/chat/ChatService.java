package com.llmtollgate.gateway.chat;

import java.time.Duration;
import java.util.List;

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
 * <p>2~3주차 범위라 사용량 제한·멱등성·캐시·모델 전환은 아직 없다.
 * 각 단계가 들어갈 자리는 주석으로 표시해 두었다.
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

    public ChatCompletionResponse complete(long customerId, String requestId, ChatCompletionRequest request) {
        if (Boolean.TRUE.equals(request.stream())) {
            throw new GatewayException(HttpStatus.NOT_IMPLEMENTED, "stream_not_supported_yet",
                    "스트리밍 응답은 아직 지원하지 않습니다. stream을 false로 보내 주세요.");
        }
        // (7~8주차) 사용량 제한
        // (4~5주차) 멱등성 확인
        // (7~8주차) 캐시 조회

        String model = resolveModel(request.model());
        int maxTokens = resolveMaxTokens(request.maxTokens());
        LlmRequest llmRequest = new LlmRequest(model,
                request.messages().stream().map(m -> new LlmRequest.Message(m.role(), m.content())).toList(),
                maxTokens);
        LlmClient client = clientFor(model);
        MDC.put("model", model);

        long reserved = pricing.maxCost(model, llmRequest.totalChars(), maxTokens);
        creditService.reserve(customerId, requestId, reserved);

        long start = System.nanoTime();
        StringBuilder content = new StringBuilder();
        LlmResult result;
        try {
            result = client.stream(llmRequest, content::append);
        } catch (LlmException e) {
            // 한 번에 받기에서는 고객이 아무것도 받지 못했으므로 예약액을 전부 돌려준다
            creditService.refund(customerId, requestId, reserved);
            long latencyMs = elapsedMs(start);
            recordFailure(customerId, requestId, model, reserved, e, latencyMs);
            // (7~8주차) e.reason().retryable()이면 예비 모델(Nova Micro)로 한 번 전환
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "all_models_unavailable",
                    "LLM 호출에 실패했습니다. 잠시 후 다시 시도해 주세요. 차감된 크레딧은 없습니다.");
        } catch (RuntimeException e) {
            creditService.refund(customerId, requestId, reserved);
            throw e;
        }

        long latencyMs = elapsedMs(start);
        long cost = pricing.cost(model, result.inputTokens(), result.outputTokens());
        long charged = Math.min(cost, reserved);
        long remaining = creditService.settle(customerId, requestId, reserved, cost);
        recordSuccess(customerId, requestId, reserved, charged, result, latencyMs);
        // (7~8주차) 캐시 저장

        return new ChatCompletionResponse(requestId, content.toString(),
                new ChatCompletionResponse.Gateway(model, false,
                        new ChatCompletionResponse.Tokens(result.inputTokens(), result.outputTokens()),
                        charged, remaining, false, result.finishReason()));
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

    private void recordSuccess(long customerId, String requestId, long reserved, long charged, LlmResult result,
                               long latencyMs) {
        callLogRepository.insert(new LlmCallLogRepository.CallLog(requestId, customerId, result.model(), "SUCCESS",
                null, result.inputTokens(), result.outputTokens(), result.usageEstimated(), reserved, charged,
                result.firstTokenMs(), latencyMs));
        Timer.builder("tollgate.llm.latency").tag("model", result.model()).tag("outcome", "success")
                .register(meters).record(Duration.ofMillis(latencyMs));
        Timer.builder("tollgate.llm.first_token").tag("model", result.model())
                .register(meters).record(Duration.ofMillis(result.firstTokenMs()));
        meters.counter("tollgate.credits.charged", "model", result.model()).increment(charged);
        log.info("LLM 호출 성공 input={} output={} charged={} firstTokenMs={} latencyMs={}",
                result.inputTokens(), result.outputTokens(), charged, result.firstTokenMs(), latencyMs);
    }

    private void recordFailure(long customerId, String requestId, String model, long reserved, LlmException e,
                               long latencyMs) {
        callLogRepository.insert(new LlmCallLogRepository.CallLog(requestId, customerId, model, "FAILED",
                e.reason().code(), null, null, false, reserved, 0, null, latencyMs));
        Timer.builder("tollgate.llm.latency").tag("model", model).tag("outcome", "failure")
                .register(meters).record(Duration.ofMillis(latencyMs));
        meters.counter("tollgate.llm.failures", "model", model, "reason", e.reason().code()).increment();
        log.warn("LLM 호출 실패 reason={} receivedChars={} latencyMs={}", e.reason(), e.receivedChars(), latencyMs);
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
