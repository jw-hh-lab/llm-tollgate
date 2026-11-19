package com.llmtollgate.gateway.common;

import org.springframework.http.HttpStatus;

/**
 * 고객에게 그대로 돌려줄 오류. code는 API 명세의 오류 코드 값과 같다.
 */
public class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GatewayException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static GatewayException invalidRequest(String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    public static GatewayException unauthorized() {
        return new GatewayException(HttpStatus.UNAUTHORIZED, "invalid_api_key", "API 키가 없거나 올바르지 않습니다.");
    }

    public static GatewayException insufficientCredits() {
        return new GatewayException(HttpStatus.PAYMENT_REQUIRED, "insufficient_credits", "크레딧 잔액이 부족합니다.");
    }

    public static GatewayException notFound(String message) {
        return new GatewayException(HttpStatus.NOT_FOUND, "not_found", message);
    }
}
