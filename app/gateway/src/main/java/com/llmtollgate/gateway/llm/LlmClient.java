package com.llmtollgate.gateway.llm;

import java.util.function.Consumer;

/**
 * LLM 호출. 고객 응답 방식과 상관없이 LLM에서는 항상 스트림으로 받는다.
 *
 * <p>2~3주차에는 조각을 모아서 한 번에 돌려주고, 4~5주차에는 onText로 받은 조각을 고객에게 바로 흘려보낸다.
 * 그래서 이 인터페이스는 스트리밍 작업 때 바뀌지 않는다.
 */
public interface LlmClient {

    /** 이 클라이언트가 처리하는 모델인지 */
    boolean supports(String model);

    /**
     * @param onText 글자 조각이 올 때마다 호출된다
     * @throws LlmException 시간 초과, 연결 실패, 공급자 오류
     */
    LlmResult stream(LlmRequest request, Consumer<String> onText);
}
