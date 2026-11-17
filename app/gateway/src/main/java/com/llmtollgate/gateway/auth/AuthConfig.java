package com.llmtollgate.gateway.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class AuthConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(ApiKeyRepository repository,
                                                                      ObjectMapper objectMapper) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(repository, objectMapper));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10); // RequestIdFilter 다음
        return registration;
    }
}
