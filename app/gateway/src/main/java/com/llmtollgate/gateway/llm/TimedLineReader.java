package com.llmtollgate.gateway.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 스트림을 한 줄씩 읽되, 다음 줄을 기다리는 시간에 상한을 둔다.
 *
 * <p>InputStream.read()는 데이터가 올 때까지 무한정 멈추기 때문에 "조각 사이 15초" 같은 규칙을 걸 수 없다.
 * 그래서 별도의 가상 스레드가 줄을 읽어 큐에 넣고, 호출하는 쪽은 큐에서 제한 시간을 두고 꺼낸다.
 * 시간이 지나면 스트림을 닫아 읽기 스레드도 함께 끝낸다.
 */
final class TimedLineReader implements AutoCloseable {

    private static final Object END = new Object();

    private final InputStream in;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Thread reader;

    TimedLineReader(InputStream in) {
        this.in = in;
        this.reader = Thread.ofVirtual().name("llm-stream-reader").start(this::readLoop);
    }

    private void readLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                queue.put(line);
            }
            queue.put(END);
        } catch (IOException e) {
            queue.offer(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return 다음 줄. 스트림이 끝났으면 null
     * @throws TimeoutException 제한 시간 안에 다음 줄이 오지 않음
     */
    String next(Duration timeout) throws TimeoutException, IOException, InterruptedException {
        long millis = Math.max(0, timeout.toMillis());
        Object item = queue.poll(millis, TimeUnit.MILLISECONDS);
        if (item == null) {
            throw new TimeoutException("다음 줄이 " + millis + "ms 안에 오지 않았습니다.");
        }
        if (item == END) {
            return null;
        }
        if (item instanceof IOException e) {
            throw e;
        }
        return (String) item;
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
            // 이미 끊긴 연결
        }
        reader.interrupt();
    }
}
