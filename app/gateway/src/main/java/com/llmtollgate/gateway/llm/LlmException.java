package com.llmtollgate.gateway.llm;

/**
 * LLM 호출 실패.
 *
 * <p>{@link Reason#retryable()}가 true인 실패만 7~8주차 모델 전환 대상이다.
 * 400 같은 요청 오류는 다른 모델에 보내도 똑같이 실패하므로 전환하지 않는다.
 *
 * @param receivedChars 실패 전에 받은 글자 수. 4~5주차 끊긴 스트림 부분 정산에 쓴다
 */
public class LlmException extends RuntimeException {

    public enum Reason {
        FIRST_TOKEN_TIMEOUT(true),
        IDLE_TIMEOUT(true),
        CONNECTION_FAILED(true),
        UPSTREAM_5XX(true),
        RATE_LIMITED(true),
        BAD_REQUEST(false),
        AUTH_FAILED(false),
        EMPTY_RESPONSE(true),
        MALFORMED_RESPONSE(true);

        private final boolean retryable;

        Reason(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }

        public String code() {
            return name().toLowerCase();
        }
    }

    private final Reason reason;
    private final long receivedChars;

    public LlmException(Reason reason, String message, long receivedChars) {
        super(message);
        this.reason = reason;
        this.receivedChars = receivedChars;
    }

    public LlmException(Reason reason, String message) {
        this(reason, message, 0);
    }

    public Reason reason() {
        return reason;
    }

    public long receivedChars() {
        return receivedChars;
    }
}
