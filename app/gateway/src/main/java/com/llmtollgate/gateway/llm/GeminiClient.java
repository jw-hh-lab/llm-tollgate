package com.llmtollgate.gateway.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmtollgate.gateway.config.GatewayProperties;

/**
 * Gemini 스트리밍 호출 (streamGenerateContent?alt=sse).
 *
 * <p>가짜 LLM 서버(fake-llm 모듈)도 같은 형식으로 응답하므로, GEMINI_BASE_URL만 바꾸면 비용 없이 테스트할 수 있다.
 *
 * <p>시간 초과는 두 가지다.
 * <ul>
 *   <li>첫 토큰 대기: 요청을 보낸 시점부터 첫 글자가 올 때까지 (응답 헤더를 기다리는 시간 포함)</li>
 *   <li>조각 사이 대기: 글자가 오다가 멈췄을 때 다음 줄까지</li>
 * </ul>
 */
@Component
public class GeminiClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final GatewayProperties.Llm config;

    public GeminiClient(HttpClient llmHttpClient, ObjectMapper mapper, GatewayProperties properties) {
        this.http = llmHttpClient;
        this.mapper = mapper;
        this.config = properties.llm();
    }

    @Override
    public boolean supports(String model) {
        return model.startsWith("gemini-");
    }

    @Override
    public LlmResult stream(LlmRequest request, Consumer<String> onText) {
        long startNanos = System.nanoTime();
        HttpResponse<InputStream> response = send(request);

        try (TimedLineReader reader = new TimedLineReader(response.body())) {
            return readStream(request, reader, startNanos, onText);
        }
    }

    private HttpResponse<InputStream> send(LlmRequest request) {
        URI uri = URI.create(config.gemini().baseUrl() + "/v1beta/models/" + request.model()
                + ":streamGenerateContent?alt=sse");
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(config.firstTokenTimeout()) // 응답 헤더까지의 대기도 첫 토큰 대기에 포함된다
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", config.gemini().apiKey() == null ? "" : config.gemini().apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(toGeminiBody(request), StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new LlmException(LlmException.Reason.FIRST_TOKEN_TIMEOUT, "응답 헤더 대기 시간 초과");
        } catch (IOException e) {
            throw new LlmException(LlmException.Reason.CONNECTION_FAILED, "LLM 연결 실패: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Reason.CONNECTION_FAILED, "호출이 중단되었습니다.");
        }

        int status = response.statusCode();
        if (status != 200) {
            String body = readErrorBody(response.body());
            log.warn("Gemini 오류 응답 status={} body={}", status, body);
            throw new LlmException(reasonFor(status), "LLM 오류 응답 " + status);
        }
        return response;
    }

    private LlmResult readStream(LlmRequest request, TimedLineReader reader, long startNanos,
                                 Consumer<String> onText) {
        Duration firstTokenTimeout = config.firstTokenTimeout();
        long firstTokenMs = -1;
        long receivedChars = 0;
        long inputTokens = -1;
        long outputTokens = -1;
        String finishReason = null;

        while (true) {
            Duration wait = firstTokenMs < 0
                    ? firstTokenTimeout.minus(Duration.ofNanos(System.nanoTime() - startNanos))
                    : config.idleTimeout();

            String line;
            try {
                line = reader.next(wait);
            } catch (TimeoutException e) {
                LlmException.Reason reason = firstTokenMs < 0
                        ? LlmException.Reason.FIRST_TOKEN_TIMEOUT
                        : LlmException.Reason.IDLE_TIMEOUT;
                throw new LlmException(reason, reason.code(), receivedChars);
            } catch (IOException e) {
                throw new LlmException(LlmException.Reason.CONNECTION_FAILED, "스트림이 끊겼습니다.", receivedChars);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException(LlmException.Reason.CONNECTION_FAILED, "호출이 중단되었습니다.", receivedChars);
            }

            if (line == null) {
                break; // 정상 종료
            }
            if (!line.startsWith("data:")) {
                continue; // SSE의 빈 줄, 주석, event: 줄
            }
            String data = line.substring(5).trim();
            if (data.isEmpty()) {
                continue;
            }

            JsonNode chunk;
            try {
                chunk = mapper.readTree(data);
            } catch (IOException e) {
                throw new LlmException(LlmException.Reason.MALFORMED_RESPONSE, "조각을 해석할 수 없습니다.", receivedChars);
            }
            if (chunk.has("error")) {
                throw new LlmException(LlmException.Reason.UPSTREAM_5XX,
                        "스트림 중 오류: " + chunk.path("error").path("message").asText(), receivedChars);
            }

            String text = extractText(chunk);
            if (!text.isEmpty()) {
                if (firstTokenMs < 0) {
                    firstTokenMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
                }
                receivedChars += text.length();
                onText.accept(text);
            }

            JsonNode candidate = chunk.path("candidates").path(0);
            if (candidate.hasNonNull("finishReason")) {
                finishReason = candidate.get("finishReason").asText();
            }
            JsonNode usage = chunk.path("usageMetadata");
            if (usage.has("promptTokenCount")) {
                inputTokens = usage.get("promptTokenCount").asLong();
                // 생각(thinking) 토큰도 출력 토큰으로 과금되므로 함께 센다
                outputTokens = usage.path("candidatesTokenCount").asLong(0)
                        + usage.path("thoughtsTokenCount").asLong(0);
            }
        }

        if (firstTokenMs < 0 && finishReason == null) {
            throw new LlmException(LlmException.Reason.EMPTY_RESPONSE, "빈 응답");
        }

        boolean estimated = inputTokens < 0;
        if (estimated) {
            log.warn("사용량 정보가 없어 글자 수로 추정합니다.");
            inputTokens = request.totalChars();
            outputTokens = receivedChars;
        }
        return new LlmResult(request.model(), inputTokens, outputTokens, estimated, finishReason,
                Math.max(firstTokenMs, 0));
    }

    private String toGeminiBody(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        StringBuilder system = new StringBuilder();

        for (LlmRequest.Message m : request.messages()) {
            if ("system".equals(m.role())) {
                if (!system.isEmpty()) {
                    system.append('\n');
                }
                system.append(m.content());
                continue;
            }
            ObjectNode content = contents.addObject();
            content.put("role", "assistant".equals(m.role()) ? "model" : "user");
            content.putArray("parts").addObject().put("text", m.content());
        }
        if (!system.isEmpty()) {
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", system.toString());
        }

        ObjectNode generation = body.putObject("generationConfig");
        generation.put("maxOutputTokens", request.maxOutputTokens());
        Integer thinkingBudget = config.gemini().thinkingBudget();
        if (thinkingBudget != null) {
            generation.putObject("thinkingConfig").put("thinkingBudget", thinkingBudget);
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String extractText(JsonNode chunk) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : chunk.path("candidates").path(0).path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) {
                continue; // 생각 과정은 고객에게 보내지 않는다
            }
            sb.append(part.path("text").asText(""));
        }
        return sb.toString();
    }

    private static LlmException.Reason reasonFor(int status) {
        if (status == 429) {
            return LlmException.Reason.RATE_LIMITED;
        }
        if (status == 401 || status == 403) {
            return LlmException.Reason.AUTH_FAILED;
        }
        if (status >= 500) {
            return LlmException.Reason.UPSTREAM_5XX;
        }
        return LlmException.Reason.BAD_REQUEST;
    }

    private static String readErrorBody(InputStream in) {
        try (in) {
            byte[] bytes = in.readNBytes(2048);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
