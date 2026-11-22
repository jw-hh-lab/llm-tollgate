-- 한 요청에 같은 종류의 원장 기록이 두 번 생기지 않게 한다 (예: 환불 작업이 두 번 돌아도 REFUND는 한 번)
-- CHARGE처럼 request_id가 NULL인 행은 UNIQUE 검사에서 제외된다
CREATE UNIQUE INDEX uk_credit_ledger_request_type ON credit_ledger (request_id, entry_type);
