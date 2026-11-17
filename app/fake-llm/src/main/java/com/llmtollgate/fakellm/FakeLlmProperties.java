package com.llmtollgate.fakellm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 가짜 응답의 기본 모양. 환경 변수로 바꿀 수 있다 (예: FAKE_LLM_CHUNK_DELAY=100ms).
 *
 * @param firstTokenDelay 요청을 받고 첫 조각을 보낼 때까지 기다리는 시간
 * @param chunkDelay      조각과 조각 사이 간격
 * @param chunks          보낼 조각 수
 * @param tokensPerChunk  조각 하나를 몇 토큰으로 셀지 (사용량 계산용)
 * @param stallDuration   #stall 지시어를 받았을 때 멈춰 있을 시간
 */
@ConfigurationProperties("fake-llm")
public record FakeLlmProperties(
        Duration firstTokenDelay,
        Duration chunkDelay,
        int chunks,
        int tokensPerChunk,
        Duration stallDuration) {
}
