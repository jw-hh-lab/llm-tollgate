package com.llmtollgate.gateway.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
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
import com.llmtollgate.gateway.common.GatewayException;

@SpringBootTest
@ActiveProfiles("test")
class CreditServiceTest {

    @Autowired
    CreditService creditService;
    @Autowired
    CustomerService customerService;
    @Autowired
    JdbcClient jdbc;

    @Test
    void 동시에_예약해도_잔액이_음수가_되지_않는다() throws Exception {
        // 흐름도 예시: 잔액 100, 80짜리 요청 5개가 동시에 온다
        long customerId = customerService.create("동시성", 100).customerId();
        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                String requestId = "req_concurrent_" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        creditService.reserve(customerId, requestId, 80);
                        return true;
                    } catch (GatewayException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
        }

        long successes = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                successes++;
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(creditService.balance(customerId)).isEqualTo(20);
    }

    @Test
    void 정산하면_예약액과_실제_비용의_차이를_돌려준다() {
        long customerId = customerService.create("정산", 1000).customerId();

        creditService.reserve(customerId, "req_settle", 300);
        long after = creditService.settle(customerId, "req_settle", 300, 120);

        assertThat(after).isEqualTo(880);
        assertThat(ledgerTypes("req_settle")).containsExactly("RESERVE", "SETTLE");
    }

    @Test
    void 실제_비용이_예약액보다_크면_예약액까지만_받는다() {
        long customerId = customerService.create("초과", 1000).customerId();

        creditService.reserve(customerId, "req_over", 300);
        long after = creditService.settle(customerId, "req_over", 300, 500);

        assertThat(after).isEqualTo(700);
    }

    @Test
    void 잔액이_부족하면_402() {
        long customerId = customerService.create("부족", 10).customerId();

        assertThatThrownBy(() -> creditService.reserve(customerId, "req_poor", 11))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo("insufficient_credits"));
        assertThat(creditService.balance(customerId)).isEqualTo(10);
    }

    private List<String> ledgerTypes(String requestId) {
        return jdbc.sql("SELECT entry_type FROM credit_ledger WHERE request_id = ? ORDER BY id")
                .param(requestId).query(String.class).list();
    }
}
