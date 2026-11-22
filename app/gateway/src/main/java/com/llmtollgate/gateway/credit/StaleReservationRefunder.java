package com.llmtollgate.gateway.credit;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정산되지 않은 채 남은 예약을 찾아 전액 환불한다 (일정표 6주차 "서버 켜질 때 오래된 예약 환불, 간단 버전").
 *
 * <p>서버는 매일 00:00에 꺼지고, 그때 처리 중이던 요청은 예약만 남고 정산되지 않는다(11/9 결정: 그냥 끄고 켜질 때 환불).
 * 그래서 앱이 켜질 때 한 번, 그 뒤로는 10분마다 "예약은 있는데 정산·환불이 없는" 요청을 찾아 돌려준다.
 *
 * <p>요청 하나는 최악의 경우에도 20초 안에 끝나므로, {@code stale-after}(기본 5분)보다 오래된 예약만 대상으로 한다.
 * 여러 Pod가 동시에 이 작업을 돌려도 원장의 (request_id, entry_type) UNIQUE 때문에 환불은 한 번만 일어난다.
 */
@Component
public class StaleReservationRefunder {

    private static final Logger log = LoggerFactory.getLogger(StaleReservationRefunder.class);

    private final JdbcClient jdbc;
    private final CreditService creditService;
    private final Duration staleAfter;

    public StaleReservationRefunder(JdbcClient jdbc, CreditService creditService,
                                    @Value("${tollgate.reconcile.stale-reservation-after:5m}") Duration staleAfter) {
        this.jdbc = jdbc;
        this.creditService = creditService;
        this.staleAfter = staleAfter;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int refunded = refundStaleReservations();
        log.info("시작 시 남은 예약 정리: {}건 환불", refunded);
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
    public void periodically() {
        refundStaleReservations();
    }

    /** @return 이번에 환불한 요청 수 */
    public int refundStaleReservations() {
        // 시간 비교는 DB 시계로 한다 (DB와 JVM의 시간대가 달라도 어긋나지 않게)
        List<Stale> stale = jdbc.sql("""
                        SELECT r.customer_id, r.request_id, r.amount
                        FROM credit_ledger r
                        WHERE r.entry_type = 'RESERVE'
                          AND r.created_at < TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6))
                          AND NOT EXISTS (
                              SELECT 1 FROM credit_ledger c
                              WHERE c.request_id = r.request_id AND c.entry_type IN ('SETTLE', 'REFUND'))
                        ORDER BY r.id""")
                .param(-staleAfter.toSeconds())
                .query((rs, i) -> new Stale(rs.getLong("customer_id"), rs.getString("request_id"),
                        -rs.getLong("amount")))
                .list();

        int refunded = 0;
        for (Stale s : stale) {
            try {
                creditService.refund(s.customerId, s.requestId, s.reserved);
                refunded++;
                log.warn("정산되지 않은 예약을 환불했습니다 requestId={} customerId={} amount={}",
                        s.requestId, s.customerId, s.reserved);
            } catch (DataIntegrityViolationException e) {
                // 다른 Pod나 늦게 끝난 요청이 먼저 정산·환불했다. 잔액 변경은 함께 롤백되었다
                log.info("이미 처리된 예약이라 건너뜁니다 requestId={}", s.requestId);
            }
        }
        return refunded;
    }

    private record Stale(long customerId, String requestId, long reserved) {
    }
}
