package com.llmtollgate.gateway.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmtollgate.gateway.admin.CustomerService;
import com.llmtollgate.gateway.credit.CreditService;
import com.llmtollgate.gateway.support.StubGeminiServer;

/**
 * 실제 HTTP로 SSE 스트림을 받아 본다 (MockMvc로는 조각 단위 전송과 고객 이탈을 재현할 수 없어서).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StreamingApiTest {

    static final StubGeminiServer GEMINI = new StubGeminiServer();
    static final String STREAM_BODY = """
            {"messages":[{"role":"user","content":"안녕?"}],"max_tokens":100,"stream":true}""";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("tollgate.llm.gemini.base-url", GEMINI::baseUrl);
    }

    @AfterAll
    static void stop() {
        GEMINI.close();
    }

    @LocalServerPort
    int port;
    @Autowired
    CustomerService customerService;
    @Autowired
    CreditService creditService;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void reset() {
        GEMINI.behave(StubGeminiServer::ok);
        GEMINI.resetCalls();
    }

    @Test
    void 정상_스트림은_조각들과_done_이벤트를_보내고_실제_사용량만큼_차감한다() throws Exception {
        var c = customerService.create("stream-ok", 100_000);

        HttpResponse<java.util.stream.Stream<String>> res = send(c.apiKey(), STREAM_BODY, null);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(res.headers().firstValue("X-Accel-Buffering")).contains("no");
        List<JsonNode> events = dataEvents(res.body().toList());
        assertThat(events.subList(0, events.size() - 1)).extracting(e -> e.path("delta").asText())
                .containsExactly("안녕", "하세요");
        JsonNode done = events.getLast();
        assertThat(done.path("done").asBoolean()).isTrue();
        long charged = done.path("credits_charged").asLong();
        assertThat(charged).isPositive();
        assertThat(creditService.balance(c.customerId())).isEqualTo(100_000 - charged);
        assertThat(types(done.path("id").asText())).containsExactly("RESERVE", "SETTLE");
    }

    @Test
    void 첫_글자_전에_실패하면_스트림이_아니라_503_JSON이고_전액_환불() throws Exception {
        var c = customerService.create("stream-503", 100_000);
        GEMINI.behave(StubGeminiServer.status(500));

        HttpResponse<java.util.stream.Stream<String>> res = send(c.apiKey(), STREAM_BODY, null);

        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(String.join("", res.body().toList())).contains("all_models_unavailable");
        assertThat(creditService.balance(c.customerId())).isEqualTo(100_000);
    }

    @Test
    void 조각을_보낸_뒤_LLM이_멈추면_error_이벤트를_보내고_전액_환불() throws Exception {
        var c = customerService.create("stream-stall", 100_000);
        GEMINI.behave(StubGeminiServer.stallAfterFirstChunk(3000)); // 테스트 설정의 조각 사이 제한은 1초

        HttpResponse<java.util.stream.Stream<String>> res = send(c.apiKey(), STREAM_BODY, null);

        List<String> lines = res.body().toList();
        assertThat(res.statusCode()).isEqualTo(200); // 이미 첫 글자를 보냈으므로 상태 코드는 200
        assertThat(lines).contains("event: error");
        JsonNode error = dataEvents(lines).getLast();
        assertThat(error.path("code").asText()).isEqualTo("upstream_stalled");
        assertThat(error.path("credits_charged").asLong()).isZero();
        assertThat(creditService.balance(c.customerId())).isEqualTo(100_000);
    }

    @Test
    void 고객이_도중에_끊으면_보낸_만큼만_받는다() throws Exception {
        var c = customerService.create("stream-cancel", 100_000);
        GEMINI.behave(StubGeminiServer.slowChunks(20, 200));

        HttpRequest req = request(c.apiKey(), STREAM_BODY, null);
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    break; // 첫 조각만 받고 끊는다
                }
            }
        }

        // 서버는 다음 조각을 쓰다가 끊긴 것을 알아채고 정산한다
        String requestId = res.headers().firstValue("X-Request-Id").orElseThrow();
        List<String> types = List.of();
        for (int i = 0; i < 50 && !types.contains("SETTLE"); i++) {
            Thread.sleep(100);
            types = types(requestId);
        }
        assertThat(types).containsExactly("RESERVE", "SETTLE");
        long balance = creditService.balance(c.customerId());
        assertThat(balance).isLessThan(100_000).isGreaterThan(100_000 - reservedOf(requestId));
        assertThat(jdbc.sql("SELECT status FROM llm_call_log WHERE request_id = ?").param(requestId)
                .query(String.class).single()).isEqualTo("CLIENT_CANCELLED");
    }

    @Test
    void 같은_Idempotency_Key로_다시_보내면_저장된_응답을_한_번에_돌려준다() throws Exception {
        var c = customerService.create("stream-idem", 100_000);

        List<JsonNode> first = dataEvents(send(c.apiKey(), STREAM_BODY, "stream-key").body().toList());
        HttpResponse<java.util.stream.Stream<String>> again = send(c.apiKey(), STREAM_BODY, "stream-key");

        assertThat(again.headers().firstValue(ChatController.REPLAYED_HEADER)).contains("true");
        JsonNode replay = mapper.readTree(String.join("", again.body().toList()));
        assertThat(replay.path("content").asText()).isEqualTo("안녕하세요");
        assertThat(replay.path("id").asText()).isEqualTo(first.getLast().path("id").asText());
        assertThat(GEMINI.calls()).isEqualTo(1);
    }

    // ---------------------------------------------------------------

    private HttpRequest request(String apiKey, String body, String idempotencyKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return b.build();
    }

    private HttpResponse<java.util.stream.Stream<String>> send(String apiKey, String body, String key)
            throws Exception {
        return http.send(request(apiKey, body, key), HttpResponse.BodyHandlers.ofLines());
    }

    private List<JsonNode> dataEvents(List<String> lines) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String l : lines) {
            if (l.startsWith("data: ")) {
                out.add(mapper.readTree(l.substring(6)));
            }
        }
        return out;
    }

    private List<String> types(String requestId) {
        return jdbc.sql("SELECT entry_type FROM credit_ledger WHERE request_id = ? ORDER BY id")
                .param(requestId).query(String.class).list().stream().collect(Collectors.toList());
    }

    private long reservedOf(String requestId) {
        return -jdbc.sql("SELECT amount FROM credit_ledger WHERE request_id = ? AND entry_type = 'RESERVE'")
                .param(requestId).query(Long.class).single();
    }
}
