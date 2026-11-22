package com.llmtollgate.gateway.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.llmtollgate.gateway.admin.CustomerService;
import com.llmtollgate.gateway.support.StubGeminiServer;

/**
 * Redis가 죽어 있어도 요청은 처리된다 (사용량 제한·캐시는 건너뜀). 흐름도의 "부가 기능 장애가 핵심 기능을 멈추지 않게".
 */
@SpringBootTest(properties = {
        "tollgate.redis.enabled=true",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",            // 아무것도 없는 포트
        "spring.data.redis.connect-timeout=200ms",
        "spring.data.redis.timeout=200ms"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedisDownTest {

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
    CustomerService customerService;

    @Test
    void Redis가_없어도_요청은_성공한다() throws Exception {
        String key = customerService.create("no-redis", 100_000).apiKey();

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":50}"))
                    .andExpect(status().isOk());
        }
        assertThat(GEMINI.calls()).isEqualTo(2); // 캐시가 없으니 두 번 다 LLM 호출
    }
}
