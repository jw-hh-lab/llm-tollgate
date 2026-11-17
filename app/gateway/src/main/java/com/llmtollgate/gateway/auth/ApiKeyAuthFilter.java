package com.llmtollgate.gateway.auth;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmtollgate.gateway.common.ErrorResponse;
import com.llmtollgate.gateway.common.GatewayException;
import com.llmtollgate.gateway.common.RequestIdFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 흐름도 1단계: /v1/** 요청의 API 키를 해시해서 고객을 찾는다.
 * 뒤의 모든 단계(사용량 제한, 크레딧, 기록)가 고객 ID를 쓰므로 가장 먼저 실행된다.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String CUSTOMER_ID_ATTRIBUTE = "tollgate.customerId";
    private static final String BEARER = "Bearer ";

    private final ApiKeyRepository apiKeyRepository;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepository, ObjectMapper objectMapper) {
        this.apiKeyRepository = apiKeyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Long> customerId = extractKey(request)
                .map(ApiKeys::hash)
                .flatMap(apiKeyRepository::findActiveCustomerId);

        if (customerId.isEmpty()) {
            writeUnauthorized(response);
            return;
        }

        request.setAttribute(CUSTOMER_ID_ATTRIBUTE, customerId.get());
        MDC.put("customerId", String.valueOf(customerId.get()));
        chain.doFilter(request, response);
    }

    private static Optional<String> extractKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        String key = header.substring(BEARER.length()).trim();
        return key.startsWith(ApiKeys.PREFIX) ? Optional.of(key) : Optional.empty();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        GatewayException e = GatewayException.unauthorized();
        response.setStatus(e.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(e.code(), e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
