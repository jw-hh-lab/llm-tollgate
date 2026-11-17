package com.llmtollgate.gateway.llm;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.llmtollgate.gateway.config.GatewayProperties;

@Configuration
public class LlmConfig {

    @Bean
    public HttpClient llmHttpClient(GatewayProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.llm().connectTimeout())
                .build();
    }
}
