package com.llmtollgate.gateway.common;

/**
 * 모든 오류 응답의 모양: {"error": {"code": "...", "message": "...", "request_id": "..."}}
 */
public record ErrorResponse(Body error) {

    public record Body(String code, String message, String requestId) {
    }

    public static ErrorResponse of(String code, String message, String requestId) {
        return new ErrorResponse(new Body(code, message, requestId));
    }
}
