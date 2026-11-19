package com.llmtollgate.gateway.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 테스트용 Gemini 흉내 서버. 응답 방식(Behavior)을 테스트마다 바꿔 끼운다.
 */
public final class StubGeminiServer implements AutoCloseable {

    @FunctionalInterface
    public interface Behavior {
        void handle(HttpExchange exchange, String requestBody) throws IOException, InterruptedException;
    }

    private final HttpServer server;
    private final AtomicReference<Behavior> behavior = new AtomicReference<>(StubGeminiServer::ok);
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    public StubGeminiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody.set(body);
            try {
                behavior.get().handle(exchange, body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 클라이언트가 먼저 끊은 경우
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void behave(Behavior b) {
        behavior.set(b);
    }

    public String lastRequestBody() {
        return lastRequestBody.get();
    }

    public int calls() {
        return calls.get();
    }

    public void resetCalls() {
        calls.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- 자주 쓰는 응답 ----

    /** "안녕" + "하세요" 두 조각, 입력 10토큰 / 출력 4토큰 + 생각 2토큰 */
    public static void ok(HttpExchange exchange, String requestBody) throws IOException {
        startSse(exchange);
        OutputStream out = exchange.getResponseBody();
        send(out, """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"생각 중","thought":true},{"text":"안녕"}]}}]}""");
        send(out, """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"하세요"}]},"finishReason":"STOP"}],\
                "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":4,"thoughtsTokenCount":2,"totalTokenCount":16}}""");
    }

    public static Behavior status(int code) {
        return (exchange, body) -> {
            byte[] bytes = "{\"error\":{\"message\":\"stub\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        };
    }

    public static Behavior delayBeforeHeaders(long millis) {
        return (exchange, body) -> {
            Thread.sleep(millis);
            ok(exchange, body);
        };
    }

    public static Behavior stallAfterFirstChunk(long millis) {
        return (exchange, body) -> {
            startSse(exchange);
            OutputStream out = exchange.getResponseBody();
            send(out, """
                    {"candidates":[{"content":{"role":"model","parts":[{"text":"안녕"}]}}]}""");
            Thread.sleep(millis);
        };
    }

    public static Behavior withoutUsage() {
        return (exchange, body) -> {
            startSse(exchange);
            send(exchange.getResponseBody(), """
                    {"candidates":[{"content":{"role":"model","parts":[{"text":"abc"}]},"finishReason":"STOP"}]}""");
        };
    }

    public static void startSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }

    public static void send(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
