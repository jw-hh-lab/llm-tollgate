package com.llmtollgate.gateway.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.llmtollgate.gateway.admin.CustomerService;

@SpringBootTest
@ActiveProfiles("test")
class StaleReservationRefunderTest {

    @Autowired
    StaleReservationRefunder refunder;
    @Autowired
    CreditService creditService;
    @Autowired
    CustomerService customerService;
    @Autowired
    JdbcClient jdbc;

    @Test
    void 서버가_죽어_정산되지_않은_오래된_예약은_전액_환불한다() {
        long customerId = customerService.create("stale", 1_000).customerId();
        creditService.reserve(customerId, "req-dead", 300);
        age("req-dead", 10);

        int refunded = refunder.refundStaleReservations();

        assertThat(refunded).isGreaterThanOrEqualTo(1);
        assertThat(creditService.balance(customerId)).isEqualTo(1_000);
        assertThat(types("req-dead")).containsExactly("RESERVE", "REFUND");
    }

    @Test
    void 방금_만든_예약과_이미_정산된_예약은_건드리지_않는다() {
        long customerId = customerService.create("fresh", 1_000).customerId();
        creditService.reserve(customerId, "req-fresh", 100);         // 아직 처리 중일 수 있음
        creditService.reserve(customerId, "req-settled", 100);
        creditService.settle(customerId, "req-settled", 100, 40);
        age("req-settled", 10);

        refunder.refundStaleReservations();

        assertThat(types("req-fresh")).containsExactly("RESERVE");
        assertThat(types("req-settled")).containsExactly("RESERVE", "SETTLE");
        assertThat(creditService.balance(customerId)).isEqualTo(1_000 - 100 - 40);
    }

    @Test
    void 환불_작업이_동시에_여러_번_돌아도_환불은_한_번만() throws Exception {
        long customerId = customerService.create("twice", 1_000).customerId();
        creditService.reserve(customerId, "req-twice", 500);
        age("req-twice", 10);

        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> job = () -> {
            start.await();
            return refunder.refundStaleReservations();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> results = List.of(pool.submit(job), pool.submit(job), pool.submit(job),
                    pool.submit(job));
            start.countDown();
            for (Future<Integer> f : results) {
                f.get();
            }
        }

        assertThat(types("req-twice")).containsExactly("RESERVE", "REFUND");
        assertThat(creditService.balance(customerId)).isEqualTo(1_000);
    }

    private void age(String requestId, int minutes) {
        jdbc.sql("UPDATE credit_ledger SET created_at = TIMESTAMPADD(MINUTE, ?, created_at) WHERE request_id = ?")
                .params(-minutes, requestId).update();
    }

    private List<String> types(String requestId) {
        return jdbc.sql("SELECT entry_type FROM credit_ledger WHERE request_id = ? ORDER BY id")
                .param(requestId).query(String.class).list();
    }
}
