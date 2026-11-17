package com.llmtollgate.fakellm;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * POST /v1beta/models/{model}:streamGenerateContent?alt=sse 흉내.
 *
 * <p>프롬프트(마지막 user 메시지)에 아래 지시어를 넣으면 장애 상황을 재현할 수 있다.
 * <ul>
 *   <li>#fail500 : 500 오류</li>
 *   <li>#fail429 : 429 (호출 한도 초과)</li>
 *   <li>#slow    : 첫 조각을 30초 뒤에 보냄 (첫 토큰 시간 초과 재현)</li>
 *   <li>#stall   : 조각 2개를 보낸 뒤 멈춤 (조각 사이 시간 초과 재현)</li>
 *   <li>#nousage : 마지막 조각에 사용량 정보를 빼고 보냄</li>
 * </ul>
 */
@RestController
public class FakeGeminiController {

    private final FakeLlmProperties props;
    private final ObjectMapper mapper;

    public FakeGeminiController(FakeLlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @PostMapping("/v1beta/models/{modelAndAction}")
    public ResponseEntity<StreamingResponseBody> streamGenerateContent(@PathVariable String modelAndAction,
                                                                       @RequestBody JsonNode body) {
        String[] parts = modelAndAction.split(":", 2);
        if (parts.length != 2 || !"streamGenerateContent".equals(parts[1])) {
            return ResponseEntity.notFound().build();
        }
        String model = parts[0];
        String prompt = lastUserText(body);
        int promptChars = totalChars(body);

        if (prompt.contains("#fail500")) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "fake internal error");
        }
        if (prompt.contains("#fail429")) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "fake rate limit");
        }

        Duration firstDelay = prompt.contains("#slow") ? Duration.ofSeconds(30) : props.firstTokenDelay();
        boolean stall = prompt.contains("#stall");
        boolean withUsage = !prompt.contains("#nousage");
        int maxTokens = body.path("generationConfig").path("maxOutputTokens").asInt(Integer.MAX_VALUE);
        int chunks = Math.max(1, Math.min(props.chunks(), maxTokens / Math.max(1, props.tokensPerChunk())));

        StreamingResponseBody stream = out -> {
            sleep(firstDelay);
            for (int i = 1; i <= chunks; i++) {
                if (stall && i == 3) {
                    sleep(props.stallDuration());
                    return;
                }
                boolean last = i == chunks;
                write(out, chunk(model, "가짜 응답 조각 " + i + ". ", last, withUsage, promptChars, i));
                if (!last) {
                    sleep(props.chunkDelay());
                }
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
    }

    private ObjectNode chunk(String model, String text, boolean last, boolean withUsage, int promptChars,
                             int chunkIndex) {
        ObjectNode node = mapper.createObjectNode();
        ObjectNode candidate = node.putArray("candidates").addObject();
        ObjectNode content = candidate.putObject("content");
        content.put("role", "model");
        content.putArray("parts").addObject().put("text", text);
        if (last) {
            candidate.put("finishReason", "STOP");
        }
        if (withUsage) {
            int promptTokens = Math.max(1, promptChars / 2);
            int outputTokens = chunkIndex * props.tokensPerChunk();
            ObjectNode usage = node.putObject("usageMetadata");
            usage.put("promptTokenCount", promptTokens);
            usage.put("candidatesTokenCount", outputTokens);
            usage.put("totalTokenCount", promptTokens + outputTokens);
        }
        node.put("modelVersion", model);
        return node;
    }

    private void write(OutputStream out, ObjectNode node) throws IOException {
        out.write(("data: " + mapper.writeValueAsString(node) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush(); // 조각마다 바로 내보낸다
    }

    private ResponseEntity<StreamingResponseBody> error(HttpStatus status, String message) {
        ObjectNode node = mapper.createObjectNode();
        ObjectNode error = node.putObject("error");
        error.put("code", status.value());
        error.put("message", message);
        StreamingResponseBody body = out -> out.write(mapper.writeValueAsBytes(node));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static String lastUserText(JsonNode body) {
        String last = "";
        for (JsonNode content : body.path("contents")) {
            if ("user".equals(content.path("role").asText("user"))) {
                StringBuilder sb = new StringBuilder();
                content.path("parts").forEach(p -> sb.append(p.path("text").asText("")));
                last = sb.toString();
            }
        }
        return last;
    }

    private static int totalChars(JsonNode body) {
        int total = 0;
        for (JsonNode content : body.path("contents")) {
            for (JsonNode p : content.path("parts")) {
                total += p.path("text").asText("").length();
            }
        }
        for (JsonNode p : body.path("systemInstruction").path("parts")) {
            total += p.path("text").asText("").length();
        }
        return total;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
