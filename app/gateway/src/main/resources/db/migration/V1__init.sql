-- 2~3주차: 고객, API 키, 크레딧 잔액, 크레딧 원장, LLM 호출 기록
-- MySQL 8과 H2(MySQL 모드) 양쪽에서 돌도록 ENUM 대신 VARCHAR를 쓴다

CREATE TABLE customer (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

-- 키 원문은 저장하지 않고 SHA-256 해시만 저장한다
CREATE TABLE api_key (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT      NOT NULL,
    key_hash    CHAR(64)    NOT NULL,
    key_prefix  VARCHAR(12) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_api_key_hash UNIQUE (key_hash),
    CONSTRAINT fk_api_key_customer FOREIGN KEY (customer_id) REFERENCES customer (id)
);

-- 잔액은 이 행 하나로 관리하고, 차감은 조건부 UPDATE 한 줄로 한다
CREATE TABLE credit_account (
    customer_id BIGINT      PRIMARY KEY,
    balance     BIGINT      NOT NULL,
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_credit_account_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT ck_credit_account_balance CHECK (balance >= 0)
);

-- 모든 잔액 변동을 한 줄씩 남긴다. amount는 잔액 기준 부호(차감은 음수)
-- entry_type: CHARGE(충전), RESERVE(예약), SETTLE(정산 후 남은 예약 반환), REFUND(전액 환불)
CREATE TABLE credit_ledger (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id   BIGINT      NOT NULL,
    request_id    VARCHAR(40),
    entry_type    VARCHAR(16) NOT NULL,
    amount        BIGINT      NOT NULL,
    balance_after BIGINT      NOT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX idx_credit_ledger_customer_created ON credit_ledger (customer_id, created_at);
CREATE INDEX idx_credit_ledger_request ON credit_ledger (request_id);

-- 정산 배치가 원장과 대조할 호출 기록
CREATE TABLE llm_call_log (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(40) NOT NULL,
    customer_id      BIGINT      NOT NULL,
    model            VARCHAR(64) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    error_code       VARCHAR(40),
    input_tokens     INT,
    output_tokens    INT,
    usage_estimated  BOOLEAN     NOT NULL DEFAULT FALSE,
    credits_reserved BIGINT      NOT NULL,
    credits_charged  BIGINT      NOT NULL,
    first_token_ms   INT,
    latency_ms       INT         NOT NULL,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_llm_call_log_request UNIQUE (request_id)
);
CREATE INDEX idx_llm_call_log_customer_created ON llm_call_log (customer_id, created_at);
