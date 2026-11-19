package com.llmtollgate.gateway.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmtollgate.gateway.config.GatewayProperties;
import com.llmtollgate.gateway.support.StubGeminiServer;

class GeminiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StubGeminiServer server;
    private GeminiClient client;

    @BeforeEach
    void setUp() {
        server = new StubGeminiServer();
        GatewayProperties props = new GatewayProperties(null,
                new GatewayProperties.Llm("gemini-2.5-flash", Duration.ofSeconds(2), Duration.ofMillis(500),
                        Duration.ofMillis(500), 1024, 4096,
                        new GatewayProperties.Gemini(server.baseUrl(), "test-key", 0)),
                null);
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        client = new GeminiClient(http, mapper, props);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private LlmRequest request(String text) {
        return new LlmRequest("gemini-2.5-flash",
                List.of(new LlmRequest.Message("system", "너는 요리사야"), new LlmRequest.Message("user", text)), 100);
    }

    @Test
    void 조각을_모으고_생각_토큰까지_출력으로_센다() throws Exception {
        StringBuilder sb = new StringBuilder();

        LlmResult result = client.stream(request("안녕?"), sb::append);

        assertThat(sb.toString()).isEqualTo("안녕하세요"); // 생각(thought) 조각은 빠진다
        assertThat(result.inputTokens()).isEqualTo(10);
        assertThat(result.outputTokens()).isEqualTo(6);   // 4 + 생각 2
        assertThat(result.usageEstimated()).isFalse();
        assertThat(result.finishReason()).isEqualTo("STOP");

        JsonNode sent = mapper.readTree(server.lastRequestBody());
        assertThat(sent.at("/systemInstruction/parts/0/text").asText()).isEqualTo("너는 요리사야");
        assertThat(sent.at("/contents/0/role").asText()).isEqualTo("user");
        assertThat(sent.at("/generationConfig/maxOutputTokens").asInt()).isEqualTo(100);
        assertThat(sent.at("/generationConfig/thinkingConfig/thinkingBudget").asInt()).isZero();
    }

    @Test
    void 첫_토큰이_늦으면_FIRST_TOKEN_TIMEOUT() {
        server.behave(StubGeminiServer.delayBeforeHeaders(1500));

        assertThatThrownBy(() -> client.stream(request("x"), t -> { }))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).reason())
                .isEqualTo(LlmException.Reason.FIRST_TOKEN_TIMEOUT);
    }

    @Test
    void 조각_사이가_멈추면_IDLE_TIMEOUT이고_받은_글자_수를_남긴다() {
        server.behave(StubGeminiServer.stallAfterFirstChunk(2000));

        assertThatThrownBy(() -> client.stream(request("x"), t -> { }))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.reason()).isEqualTo(LlmException.Reason.IDLE_TIMEOUT);
                    assertThat(e.receivedChars()).isEqualTo(2);
                });
    }

    @Test
    void 상태_코드별로_실패_이유를_나눈다() {
        server.behave(StubGeminiServer.status(500));
        assertThatThrownBy(() -> client.stream(request("x"), t -> { }))
                .extracting(e -> ((LlmException) e).reason()).isEqualTo(LlmException.Reason.UPSTREAM_5XX);

        server.behave(StubGeminiServer.status(429));
        assertThatThrownBy(() -> client.stream(request("x"), t -> { }))
                .extracting(e -> ((LlmException) e).reason()).isEqualTo(LlmException.Reason.RATE_LIMITED);

        server.behave(StubGeminiServer.status(400));
        assertThatThrownBy(() -> client.stream(request("x"), t -> { }))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.reason().retryable()).isFalse());
    }

    @Test
    void 사용량이_없으면_글자_수로_추정한다() {
        server.behave(StubGeminiServer.withoutUsage());

        LlmResult result = client.stream(request("12345"), t -> { });

        assertThat(result.usageEstimated()).isTrue();
        assertThat(result.inputTokens()).isEqualTo("너는 요리사야".length() + 5);
        assertThat(result.outputTokens()).isEqualTo(3);
    }
}
