package com.llmtollgate.fakellm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"fake-llm.first-token-delay=10ms", "fake-llm.chunk-delay=10ms", "fake-llm.chunks=3"})
class FakeGeminiControllerTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private HttpResponse<java.util.stream.Stream<String>> call(String prompt) throws Exception {
        String body = """
                {"contents":[{"role":"user","parts":[{"text":"%s"}]}],"generationConfig":{"maxOutputTokens":100}}"""
                .formatted(prompt);
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofLines());
    }

    @Test
    void Gemini와_같은_SSE_형식으로_조각을_보내고_마지막에_사용량을_붙인다() throws Exception {
        HttpResponse<java.util.stream.Stream<String>> res = call("안녕");

        assertThat(res.statusCode()).isEqualTo(200);
        List<String> data = res.body().filter(l -> l.startsWith("data: ")).toList();
        assertThat(data).hasSize(3);
        assertThat(data.get(2)).contains("\"finishReason\":\"STOP\"").contains("\"candidatesTokenCount\":15");
    }

    @Test
    void 지시어로_오류를_재현한다() throws Exception {
        assertThat(call("#fail500").statusCode()).isEqualTo(500);
        assertThat(call("#fail429").statusCode()).isEqualTo(429);
    }
}
