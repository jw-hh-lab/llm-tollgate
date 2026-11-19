package com.llmtollgate.gateway.credit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.llmtollgate.gateway.common.GatewayException;

/**
 * 흐름도 5단계(크레딧 예약)와 7단계(정산).
 *
 * <p>예약은 "잔액 >= 예약액일 때만 줄여라"를 UPDATE 한 줄로 실행한다. DB가 확인과 차감을 한 번에 처리하므로
 * 같은 고객의 요청이 동시에 와도 잔액이 음수가 되지 않는다. 각 메서드는 짧은 트랜잭션이고,
 * LLM 호출 중에는 트랜잭션을 잡고 있지 않는다.
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private final JdbcClient jdbc;

    public CreditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void openAccount(long customerId) {
        jdbc.sql("INSERT INTO credit_account (customer_id, balance) VALUES (?, 0)")
                .param(customerId)
                .update();
    }

    /** 충전. 관리자 API에서만 쓴다. */
    @Transactional
    public long charge(long customerId, long amount) {
        if (amount <= 0) {
            throw GatewayException.invalidRequest("충전 금액은 0보다 커야 합니다.");
        }
        int updated = jdbc.sql("""
                        UPDATE credit_account SET balance = balance + ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE customer_id = ?""")
                .params(amount, customerId)
                .update();
        if (updated == 0) {
            throw GatewayException.notFound("고객을 찾을 수 없습니다.");
        }
        return appendLedger(customerId, null, LedgerType.CHARGE, amount);
    }

    /**
     * 최대 비용만큼 잔액을 먼저 잡아 둔다.
     *
     * @return 예약 후 잔액
     * @throws GatewayException 잔액이 부족하면 402
     */
    @Transactional
    public long reserve(long customerId, String requestId, long amount) {
        int updated = jdbc.sql("""
                        UPDATE credit_account SET balance = balance - ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE customer_id = ? AND balance >= ?""")
                .params(amount, customerId, amount)
                .update();
        if (updated == 0) {
            throw GatewayException.insufficientCredits();
        }
        return appendLedger(customerId, requestId, LedgerType.RESERVE, -amount);
    }

    /**
     * 실제 비용으로 정산하고 예약액과의 차이를 돌려준다.
     * 실제 비용이 예약액보다 크면 예약액까지만 받는다(예약액이 고객이 동의한 최대치이기 때문).
     *
     * @return 정산 후 잔액
     */
    @Transactional
    public long settle(long customerId, String requestId, long reserved, long actualCost) {
        long charged = Math.min(actualCost, reserved);
        if (actualCost > reserved) {
            log.warn("실제 비용이 예약액을 넘었습니다. reserved={}, actual={}", reserved, actualCost);
        }
        long giveBack = reserved - charged;
        return giveBack(customerId, requestId, LedgerType.SETTLE, giveBack);
    }

    /** 호출이 실패했을 때 예약액 전부를 돌려준다. */
    @Transactional
    public long refund(long customerId, String requestId, long reserved) {
        return giveBack(customerId, requestId, LedgerType.REFUND, reserved);
    }

    public long balance(long customerId) {
        return jdbc.sql("SELECT balance FROM credit_account WHERE customer_id = ?")
                .param(customerId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> GatewayException.notFound("고객을 찾을 수 없습니다."));
    }

    private long giveBack(long customerId, String requestId, LedgerType type, long amount) {
        if (amount > 0) {
            jdbc.sql("""
                            UPDATE credit_account SET balance = balance + ?, updated_at = CURRENT_TIMESTAMP(6)
                            WHERE customer_id = ?""")
                    .params(amount, customerId)
                    .update();
        }
        // 반환액이 0이어도 기록을 남긴다. 정산 배치가 "예약은 있는데 정산이 없는 요청"을 찾을 때 쓴다
        return appendLedger(customerId, requestId, type, amount);
    }

    private long appendLedger(long customerId, String requestId, LedgerType type, long amount) {
        long balanceAfter = balance(customerId);
        jdbc.sql("""
                        INSERT INTO credit_ledger (customer_id, request_id, entry_type, amount, balance_after)
                        VALUES (?, ?, ?, ?, ?)""")
                .params(customerId, requestId, type.name(), amount, balanceAfter)
                .update();
        return balanceAfter;
    }

    public enum LedgerType {
        CHARGE, RESERVE, SETTLE, REFUND
    }
}
