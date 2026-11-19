package com.llmtollgate.fakellm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 부하 테스트용 가짜 LLM 서버. Gemini streamGenerateContent(SSE)와 같은 형식으로 응답한다.
 * 게이트웨이의 GEMINI_BASE_URL을 이 서버 주소로 바꾸면 LLM 비용 없이 테스트할 수 있다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FakeLlmApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakeLlmApplication.class, args);
    }
}
