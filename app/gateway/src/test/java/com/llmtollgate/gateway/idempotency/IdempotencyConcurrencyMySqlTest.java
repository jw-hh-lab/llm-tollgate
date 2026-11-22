package com.llmtollgate.gateway.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.llmtollgate.gateway.admin.CustomerService;
import com.llmtollgate.gateway.chat.ChatCompletionRequest;
import com.llmtollgate.gateway.chat.IdempotentChatService;
import com.llmtollgate.gateway.common.GatewayException;
import com.llmtollgate.gateway.credit.CreditService;
import com.llmtollgate.gateway.support.StubGeminiServer;

/**
 * 실제 MySQL 8.4에서 같은 Idempotency-Key로 동시에 온 요청이 한 번만 처리되는지 확인한다.
 * (고객 앱이 응답을 못 받고 재시도를 여러 번 겹쳐 보낸 상황)
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyConcurrencyMySqlTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static final StubGeminiServer GEMINI = new StubGeminiServer();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("tollgate.llm.gemini.base-url", GEMINI::baseUrl);
    }

    @AfterAll
    static void stop() {
        GEMINI.close();
    }

    @Autowired
    IdempotentChatService chat;
    @Autowired
    CustomerService customerService;
    @Autowired
    CreditService creditService;
    @Autowired
    JdbcClient jdbc;

    final ChatCompletionRequest request = new ChatCompletionRequest("auto",
            List.of(new ChatCompletionRequest.Message("user", "일주일 레시피")), 100, false);

    @BeforeEach
    void reset() {
        GEMINI.resetCalls();
    }

    @Test
    void 같은_키로_동시에_10개가_와도_LLM_호출과_차감은_한_번() throws Exception {
        // LLM이 0.5초 걸리게 해서 10개 요청이 처리 중에 확실히 겹치게 한다
        GEMINI.behave(StubGeminiServer.delayBeforeHeaders(500));
        long customerId = customerService.create("idem-same", 100_000).customerId();

        List<String> outcomes = runConcurrently(10, i -> {
            try {
                IdempotentChatService.Result r = chat.handle(customerId, "req-same-" + i, "retry-key", request);
                return r.replayed() ? "replayed" : "processed";
            } catch (GatewayException e) {
                return e.code();
            }
        });

        assertThat(outcomes).filteredOn("processed"::equals).hasSize(1);
        assertThat(outcomes).allMatch(o -> o.equals("processed") || o.equals("idempotency_in_progress")
                || o.equals("replayed"));
        assertThat(GEMINI.calls()).isEqualTo(1);
        assertThat(countLedger(customerId, "RESERVE")).isEqualTo(1);

        // 처리가 끝난 뒤 같은 키로 다시 보내면 저장된 응답
        IdempotentChatService.Result again = chat.handle(customerId, "req-same-late", "retry-key", request);
        assertThat(again.replayed()).isTrue();
        assertThat(GEMINI.calls()).isEqualTo(1);
        assertLedgerMatchesBalance(customerId);
    }

    @Test
    void 키가_다르면_동시에_와도_모두_따로_처리된다() throws Exception {
        GEMINI.behave(StubGeminiServer::ok);
        long customerId = customerService.create("idem-diff", 100_000).customerId();

        List<String> outcomes = runConcurrently(50, i -> {
            IdempotentChatService.Result r = chat.handle(customerId, "req-diff-" + i, "key-" + i, request);
            return r.replayed() ? "replayed" : "processed";
        });

        assertThat(outcomes).containsOnly("processed").hasSize(50);
        assertThat(GEMINI.calls()).isEqualTo(50);
        assertThat(countLedger(customerId, "RESERVE")).isEqualTo(50);
        assertLedgerMatchesBalance(customerId);
    }

    // ---------------------------------------------------------------

    private long countLedger(long customerId, String type) {
        return jdbc.sql("SELECT COUNT(*) FROM credit_ledger WHERE customer_id = ? AND entry_type = ?")
                .params(customerId, type).query(Long.class).single();
    }

    private void assertLedgerMatchesBalance(long customerId) {
        long ledgerSum = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM credit_ledger WHERE customer_id = ?")
                .param(customerId).query(Long.class).single();
        assertThat(creditService.balance(customerId)).isEqualTo(ledgerSum);
    }

    @FunctionalInterface
    interface Task {
        String run(int index) throws Exception;
    }

    private List<String> runConcurrently(int n, Task task) throws Exception {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.run(index);
                }));
            }
            ready.await();
            start.countDown();
        }
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get());
        }
        return results;
    }
}
