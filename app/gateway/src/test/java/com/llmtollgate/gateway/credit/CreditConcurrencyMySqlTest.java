package com.llmtollgate.gateway.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import com.llmtollgate.gateway.chat.ChatService;
import com.llmtollgate.gateway.common.GatewayException;
import com.llmtollgate.gateway.support.StubGeminiServer;

/**
 * 실제 MySQL 8.4에서 크레딧 예약·정산의 동시성을 검증하고 측정한다.
 *
 * <p>H2 테스트(CreditServiceTest)는 요청 5개로 원리만 확인했다. 여기서는 배포 환경과 같은 MySQL(InnoDB)에서
 * 수백 개의 동시 요청을 보내 "잔액이 음수가 되지 않는다", "잔액 = 원장 합계"를 확인하고, 예약에 걸린 시간도 잰다.
 * 측정 결과는 build/reports/concurrency/에 마크다운으로 남긴다.
 *
 * <p>Docker가 없으면 이 클래스 전체를 건너뛴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.MethodName.class) // 시나리오 번호 순서대로 (첫 시나리오가 JVM 예열을 떠안지 않게 0번 예열을 둔다)
class CreditConcurrencyMySqlTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("tollgate")
            .withUsername("tollgate")
            .withPassword("tollgate");

    static final StubGeminiServer GEMINI = new StubGeminiServer();
    static final List<String> REPORT = Collections.synchronizedList(new ArrayList<>());

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("tollgate.llm.gemini.base-url", GEMINI::baseUrl);
    }

    @AfterAll
    static void writeReport() throws IOException {
        GEMINI.close();
        Path dir = Path.of("build", "reports", "concurrency");
        Files.createDirectories(dir);
        String body = "# 크레딧 동시성 측정 (MySQL " + MYSQL.getDockerImageName() + ")\n\n"
                + "커넥션 풀: HikariCP 기본 10개, 스레드 풀: 요청 수만큼\n\n"
                + String.join("\n\n", REPORT) + "\n";
        Files.writeString(dir.resolve("mysql-credit.md"), body, StandardCharsets.UTF_8);
        System.out.println(body);
    }

    @Autowired
    CreditService creditService;
    @Autowired
    CustomerService customerService;
    @Autowired
    ChatService chatService;
    @Autowired
    JdbcClient jdbc;

    @Test
    void 시나리오0_예열() throws Exception {
        // JVM·커넥션 풀·MySQL 버퍼를 데운다. 측정에는 넣지 않는다
        long customerId = customerService.create("warmup", 1_000_000).customerId();
        runConcurrently(200, i -> {
            creditService.reserve(customerId, "w-" + i, 1);
            creditService.settle(customerId, "w-" + i, 1, 1);
            return true;
        });
        GEMINI.behave(StubGeminiServer::ok);
        ChatCompletionRequest req = new ChatCompletionRequest("auto",
                List.of(new ChatCompletionRequest.Message("user", "hi")), 100, false);
        runConcurrently(50, i -> {
            chatService.complete(customerId, "w-chat-" + i, req);
            return true;
        });
    }

    @Test
    void 시나리오1_잔액_100에_80짜리_예약_100개가_동시에_오면_1개만_성공() throws Exception {
        long customerId = customerService.create("s1", 100).customerId();

        Result r = runConcurrently(100, i -> {
            creditService.reserve(customerId, "s1-" + i, 80);
            return true;
        });

        assertThat(r.successes).isEqualTo(1);
        assertThat(r.rejected).isEqualTo(99);
        assertThat(creditService.balance(customerId)).isEqualTo(20);
        assertLedgerMatchesBalance(customerId);
        REPORT.add(r.toMarkdown("시나리오 1: 잔액 100, 80짜리 예약 100개 동시", "성공 1 / 거절 99 / 최종 잔액 20"));
    }

    @Test
    void 시나리오2_잔액이_딱_100번분일_때_요청_200개가_동시에_오면_정확히_100개만_성공() throws Exception {
        long customerId = customerService.create("s2", 10_000).customerId();

        Result r = runConcurrently(200, i -> {
            creditService.reserve(customerId, "s2-" + i, 100);
            return true;
        });

        assertThat(r.successes).isEqualTo(100);
        assertThat(r.rejected).isEqualTo(100);
        assertThat(creditService.balance(customerId)).isZero();
        assertLedgerMatchesBalance(customerId);
        REPORT.add(r.toMarkdown("시나리오 2: 잔액 10,000, 100짜리 예약 200개 동시", "성공 100 / 거절 100 / 최종 잔액 0"));
    }

    @Test
    void 시나리오3_예약과_정산이_섞여도_잔액은_원장_합계와_같다() throws Exception {
        long initial = 50_000;
        long customerId = customerService.create("s3", initial).customerId();
        AtomicLong charged = new AtomicLong();

        Result r = runConcurrently(300, i -> {
            String requestId = "s3-" + i;
            long reserved = 300;
            creditService.reserve(customerId, requestId, reserved);
            long actual = ThreadLocalRandom.current().nextLong(0, reserved + 1);
            creditService.settle(customerId, requestId, reserved, actual);
            charged.addAndGet(actual);
            return true;
        });

        long balance = creditService.balance(customerId);
        assertThat(balance).isGreaterThanOrEqualTo(0);
        assertThat(balance).isEqualTo(initial - charged.get());
        assertLedgerMatchesBalance(customerId);
        REPORT.add(r.toMarkdown("시나리오 3: 예약 300 + 무작위 정산, 300개 동시 (잔액 50,000)",
                "성공 " + r.successes + " / 거절 " + r.rejected + " / 최종 잔액 " + balance
                        + " = 초기 50,000 − 실제 차감 " + charged.get()));
    }

    @Test
    void 시나리오4_실제_요청_흐름으로_동시_호출해도_잔액과_원장이_맞다() throws Exception {
        // 인증을 뺀 ChatService 전체 흐름: 예약 → (가짜) Gemini 스트림 호출 → 정산 → 호출 기록
        GEMINI.behave(StubGeminiServer::ok);
        long initial = 1_000;
        long customerId = customerService.create("s4", initial).customerId();
        ChatCompletionRequest req = new ChatCompletionRequest("auto",
                List.of(new ChatCompletionRequest.Message("user", "hi")), 100, false);

        Result r = runConcurrently(100, i -> {
            chatService.complete(customerId, "s4-" + i, req);
            return true;
        });

        long balance = creditService.balance(customerId);
        long successLogs = jdbc.sql("SELECT COUNT(*) FROM llm_call_log WHERE customer_id = ? AND status = 'SUCCESS'")
                .param(customerId).query(Long.class).single();
        long chargedSum = jdbc.sql("SELECT COALESCE(SUM(credits_charged), 0) FROM llm_call_log WHERE customer_id = ?")
                .param(customerId).query(Long.class).single();

        assertThat(balance).isGreaterThanOrEqualTo(0);
        assertThat(successLogs).isEqualTo(r.successes);
        assertThat(balance).isEqualTo(initial - chargedSum); // 원장 = 호출 기록 (정산 배치가 할 대조)
        assertLedgerMatchesBalance(customerId);
        REPORT.add(r.toMarkdown("시나리오 4: 실제 흐름(예약→가짜 LLM→정산) 100개 동시 (잔액 1,000)",
                "성공 " + r.successes + " / 잔액 부족 거절 " + r.rejected + " / 최종 잔액 " + balance
                        + " = 초기 1,000 − 호출 기록 차감 합계 " + chargedSum));
    }

    @Test
    void 시나리오5_같은_부하를_고객_20명에게_나누면_줄서기가_사라진다() throws Exception {
        // 시나리오 2와 같은 요청 200개를 고객 20명(각 10개)에게 나눠 보낸다.
        // 잔액 행 잠금은 고객별이라, 고객이 다르면 서로 기다리지 않는다
        int customers = 20;
        long[] ids = new long[customers];
        for (int c = 0; c < customers; c++) {
            ids[c] = customerService.create("s5-" + c, 1_000).customerId();
        }

        Result r = runConcurrently(200, i -> {
            creditService.reserve(ids[i % customers], "s5-" + i, 100);
            return true;
        });

        assertThat(r.successes).isEqualTo(200);
        for (long id : ids) {
            assertThat(creditService.balance(id)).isZero();
            assertLedgerMatchesBalance(id);
        }
        REPORT.add(r.toMarkdown("시나리오 5: 시나리오 2와 같은 200개를 고객 20명에게 나눔 (고객당 잔액 1,000)",
                "성공 200 / 거절 0 / 고객 20명 모두 최종 잔액 0"));
    }

    // ---------------------------------------------------------------

    private void assertLedgerMatchesBalance(long customerId) {
        long ledgerSum = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM credit_ledger WHERE customer_id = ?")
                .param(customerId).query(Long.class).single();
        assertThat(creditService.balance(customerId)).as("잔액 = 원장 합계").isEqualTo(ledgerSum);
    }

    @FunctionalInterface
    interface Task {
        boolean run(int index) throws Exception;
    }

    private Result runConcurrently(int requests, Task task) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<long[]>> futures = new ArrayList<>();
        long begin;

        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    long t0 = System.nanoTime();
                    boolean ok;
                    try {
                        ok = task.run(index);
                    } catch (GatewayException e) {
                        ok = false; // 잔액 부족(402)
                    }
                    return new long[] {ok ? 1 : 0, System.nanoTime() - t0};
                }));
            }
            ready.await();
            begin = System.nanoTime();
            start.countDown();
        }
        long totalNanos = System.nanoTime() - begin;

        List<Long> latencies = new ArrayList<>();
        int successes = 0;
        for (Future<long[]> f : futures) {
            long[] v = f.get(); // 예상하지 못한 예외(데드락 등)는 여기서 테스트 실패로 드러난다
            successes += (int) v[0];
            latencies.add(v[1]);
        }
        Collections.sort(latencies);
        return new Result(requests, successes, requests - successes, latencies, totalNanos);
    }

    record Result(int requests, int successes, int rejected, List<Long> latenciesNanos, long totalNanos) {

        long percentileMs(double p) {
            int idx = (int) Math.ceil(p * latenciesNanos.size()) - 1;
            return Duration.ofNanos(latenciesNanos.get(Math.max(0, idx))).toMillis();
        }

        String toMarkdown(String title, String outcome) {
            long totalMs = Duration.ofNanos(totalNanos).toMillis();
            return "## " + title + "\n\n"
                    + "| 항목 | 값 |\n| --- | --- |\n"
                    + "| 결과 | " + outcome + " |\n"
                    + "| 요청 하나 처리 시간 p50 / p95 / 최대 | " + percentileMs(0.50) + " / " + percentileMs(0.95)
                    + " / " + percentileMs(1.0) + " ms |\n"
                    + "| 전체 소요 | " + totalMs + " ms (" + (requests * 1000L / Math.max(1, totalMs)) + " 요청/초) |";
        }
    }
}
