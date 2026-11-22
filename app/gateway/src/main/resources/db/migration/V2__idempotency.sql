-- 멱등성 키: 같은 고객이 같은 Idempotency-Key로 보낸 요청은 한 번만 처리한다
-- (고객, 키) UNIQUE가 동시에 들어온 같은 요청 중 하나만 통과시키는 잠금 역할을 한다
CREATE TABLE idempotency_key (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id   BIGINT       NOT NULL,
    idem_key      VARCHAR(128) NOT NULL,
    request_hash  CHAR(64)     NOT NULL,   -- 같은 키로 다른 요청을 보내면 거절하기 위한 본문 해시
    status        VARCHAR(16)  NOT NULL,   -- IN_PROGRESS, COMPLETED
    request_id    VARCHAR(40)  NOT NULL,   -- 처음 처리한 요청의 requestId
    response_body MEDIUMTEXT,              -- COMPLETED일 때 그대로 다시 돌려줄 응답
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uk_idempotency_customer_key UNIQUE (customer_id, idem_key)
);
CREATE INDEX idx_idempotency_created ON idempotency_key (created_at);
