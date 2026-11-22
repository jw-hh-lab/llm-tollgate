package com.llmtollgate.gateway.chat;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmtollgate.gateway.config.GatewayProperties;
import com.llmtollgate.gateway.credit.Pricing;
import com.llmtollgate.gateway.support.StubGeminiServer;

/**
 * 고객 등록 → API 키로 호출 → 크레딧 차감까지 HTTP로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatApiTest {

    static final StubGeminiServer GEMINI = new StubGeminiServer();
    static final String BODY = """
            {"model":"auto","messages":[{"role":"user","content":"안녕?"}],"max_tokens":100}""";

    @DynamicPropertySource
    static void geminiUrl(DynamicPropertyRegistry registry) {
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
    Pricing pricing;
    @Autowired
    GatewayProperties properties;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        GEMINI.behave(StubGeminiServer::ok);
        GEMINI.resetCalls();
    }

    @Test
    void 키가_없으면_401() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"))
                .andExpect(jsonPath("$.error.request_id").exists());
    }

    @Test
    void 관리자_토큰이_틀리면_고객을_만들_수_없다() throws Exception {
        mvc.perform(post("/admin/customers").header("X-Admin-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 성공하면_실제_사용량만큼만_차감한다() throws Exception {
        Customer c = createCustomer(100_000);

        String json = mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode res = mapper.readTree(json);
        long expectedCost = pricing.cost(properties.llm().primaryModel(), 10, 6);
        assertThat(res.path("content").asText()).isEqualTo("안녕하세요");
        assertThat(res.at("/gateway/model_used").asText()).isEqualTo(properties.llm().primaryModel());
        assertThat(res.at("/gateway/tokens/output").asLong()).isEqualTo(6);
        assertThat(res.at("/gateway/credits_charged").asLong()).isEqualTo(expectedCost);
        assertThat(res.at("/gateway/credits_remaining").asLong()).isEqualTo(100_000 - expectedCost);

        String requestId = res.path("id").asText();
        assertThat(ledgerTypes(requestId)).containsExactly("RESERVE", "SETTLE");
        assertThat(jdbc.sql("SELECT status FROM llm_call_log WHERE request_id = ?").param(requestId)
                .query(String.class).single()).isEqualTo("SUCCESS");

        mvc.perform(get("/v1/credits").header("Authorization", "Bearer " + c.apiKey))
                .andExpect(jsonPath("$.balance").value(100_000 - expectedCost));
    }

    @Test
    void 잔액이_부족하면_402이고_LLM을_부르지_않는다() throws Exception {
        Customer c = createCustomer(1);

        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error.code").value("insufficient_credits"));
        assertThat(GEMINI.calls()).isZero();
    }

    @Test
    void LLM이_실패하면_503이고_예약액을_전부_돌려준다() throws Exception {
        Customer c = createCustomer(100_000);
        GEMINI.behave(StubGeminiServer.status(500));

        String json = mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("all_models_unavailable"))
                .andReturn().getResponse().getContentAsString();

        String requestId = mapper.readTree(json).at("/error/request_id").asText();
        assertThat(ledgerTypes(requestId)).containsExactly("RESERVE", "REFUND");
        mvc.perform(get("/v1/credits").header("Authorization", "Bearer " + c.apiKey))
                .andExpect(jsonPath("$.balance").value(100_000));
    }

    @Test
    void 스트리밍_요청은_아직_501() throws Exception {
        Customer c = createCustomer(100_000);

        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error.code").value("stream_not_supported_yet"));
    }

    @Test
    void 잘못된_역할이면_400() throws Exception {
        Customer c = createCustomer(100_000);

        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"robot\",\"content\":\"hi\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    // ---- 멱등성 (Idempotency-Key) ----

    @Test
    void 같은_키로_다시_보내면_저장된_응답을_돌려주고_한_번만_차감한다() throws Exception {
        Customer c = createCustomer(100_000);

        String first = mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .header("Idempotency-Key", "order-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(ChatController.REPLAYED_HEADER))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .header("Idempotency-Key", "order-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string(ChatController.REPLAYED_HEADER, "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(second)).isEqualTo(mapper.readTree(first)); // id까지 같은 응답
        assertThat(GEMINI.calls()).isEqualTo(1);
        long charged = mapper.readTree(first).at("/gateway/credits_charged").asLong();
        mvc.perform(get("/v1/credits").header("Authorization", "Bearer " + c.apiKey))
                .andExpect(jsonPath("$.balance").value(100_000 - charged));
    }

    @Test
    void 같은_키로_다른_내용을_보내면_422() throws Exception {
        Customer c = createCustomer(100_000);
        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .header("Idempotency-Key", "order-2").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .header("Idempotency-Key", "order-2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"다른 질문\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"));
    }

    @Test
    void 실패한_요청은_같은_키로_다시_시도할_수_있다() throws Exception {
        Customer c = createCustomer(100_000);
        GEMINI.behave(StubGeminiServer.status(500));
        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .header("Idempotency-Key", "order-3").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());

        GEMINI.behave(StubGeminiServer::ok);
        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .header("Idempotency-Key", "order-3").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(ChatController.REPLAYED_HEADER));
        assertThat(GEMINI.calls()).isEqualTo(2);
    }

    @Test
    void 키가_너무_길면_400() throws Exception {
        Customer c = createCustomer(100_000);
        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + c.apiKey)
                        .header("Idempotency-Key", "k".repeat(129)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
        assertThat(GEMINI.calls()).isZero();
    }

    private Customer createCustomer(long credits) throws Exception {
        String json = mvc.perform(post("/admin/customers").header("X-Admin-Token", "test-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"레시피앱\",\"initial_credits\":" + credits + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(json);
        return new Customer(node.path("customer_id").asLong(), node.path("api_key").asText());
    }

    private java.util.List<String> ledgerTypes(String requestId) {
        return jdbc.sql("SELECT entry_type FROM credit_ledger WHERE request_id = ? ORDER BY id")
                .param(requestId).query(String.class).list();
    }

    record Customer(long id, String apiKey) {
    }
}
