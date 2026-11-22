package com.llmtollgate.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmtollgate.gateway.admin.CustomerService;
import com.llmtollgate.gateway.support.StubGeminiServer;

/**
 * 실제 Redis 7.4(k8s 매니페스트와 같은 버전)로 사용량 제한과 응답 캐시를 확인한다.
 * 하루 한도를 3으로 낮춰서 테스트한다 (분 단위 창은 테스트가 분 경계를 넘으면 결과가 흔들려서 크게 둔다).
 */
@SpringBootTest(properties = {
        "tollgate.redis.enabled=true",
        "tollgate.redis.rate-limit.per-minute=1000",
        "tollgate.redis.rate-limit.per-day=3"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RedisFeaturesTest {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static final StubGeminiServer GEMINI = new StubGeminiServer();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("tollgate.llm.gemini.base-url", GEMINI::baseUrl);
    }

    @AfterAll
    static void stop() {
        GEMINI.close();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    CustomerService customerService;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        GEMINI.behave(StubGeminiServer::ok);
        GEMINI.resetCalls();
    }

    private String body(String question) {
        return "{\"messages\":[{\"role\":\"user\",\"content\":\"" + question + "\"}],\"max_tokens\":100}";
    }

    private org.springframework.test.web.servlet.ResultActions call(String key, String body) throws Exception {
        return mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 하루_한도를_넘으면_429와_Retry_After를_주고_다른_고객은_영향이_없다() throws Exception {
        String a = customerService.create("limit-a", 100_000).apiKey();
        String b = customerService.create("limit-b", 100_000).apiKey();

        for (int i = 0; i < 3; i++) {
            call(a, body("질문 " + i)).andExpect(status().isOk());
        }
        call(a, body("네 번째")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("rate_limited"))
                .andExpect(header().exists("Retry-After"));

        call(b, body("다른 고객")).andExpect(status().isOk());
        assertThat(GEMINI.calls()).isEqualTo(4); // 거절된 요청은 LLM을 부르지 않는다
    }

    @Test
    void 같은_질문은_캐시에서_돌려주고_차감하지_않는다() throws Exception {
        String key = customerService.create("cache", 100_000).apiKey();

        JsonNode first = mapper.readTree(call(key, body("레시피")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode second = mapper.readTree(call(key, body("레시피")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(first.at("/gateway/cache_hit").asBoolean()).isFalse();
        assertThat(second.at("/gateway/cache_hit").asBoolean()).isTrue();
        assertThat(second.at("/gateway/credits_charged").asLong()).isZero();
        assertThat(second.path("content").asText()).isEqualTo(first.path("content").asText());
        assertThat(GEMINI.calls()).isEqualTo(1);
        long charged = first.at("/gateway/credits_charged").asLong();
        mvc.perform(get("/v1/credits").header("Authorization", "Bearer " + key))
                .andExpect(jsonPath("$.balance").value(100_000 - charged));
        assertThat(jdbc.sql("SELECT status FROM llm_call_log WHERE request_id = ?")
                .param(second.path("id").asText()).query(String.class).single()).isEqualTo("CACHE_HIT");
    }

    @Test
    void 캐시는_고객마다_따로다() throws Exception {
        String a = customerService.create("cache-a", 100_000).apiKey();
        String b = customerService.create("cache-b", 100_000).apiKey();

        call(a, body("같은 질문")).andExpect(status().isOk());
        JsonNode other = mapper.readTree(call(b, body("같은 질문")).andReturn().getResponse().getContentAsString());

        assertThat(other.at("/gateway/cache_hit").asBoolean()).isFalse();
        assertThat(GEMINI.calls()).isEqualTo(2);
    }

    @Test
    void 캐시된_답은_스트림으로도_돌려준다() throws Exception {
        String key = customerService.create("cache-stream", 100_000).apiKey();
        call(key, body("스트림 캐시")).andExpect(status().isOk());

        String sse = call(key, "{\"messages\":[{\"role\":\"user\",\"content\":\"스트림 캐시\"}],\"max_tokens\":100,"
                + "\"stream\":true}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(sse).contains("\"delta\":\"안녕하세요\"").contains("\"cache_hit\":true")
                .contains("\"credits_charged\":0");
        assertThat(GEMINI.calls()).isEqualTo(1);
    }
}
