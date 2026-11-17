package com.llmtollgate.gateway.credit;

import org.springframework.stereotype.Component;

import com.llmtollgate.gateway.common.GatewayException;
import com.llmtollgate.gateway.config.GatewayProperties;
import com.llmtollgate.gateway.config.GatewayProperties.ModelPrice;

/**
 * 토큰 수를 크레딧으로 바꾼다. 단가는 application.yml의 tollgate.pricing.
 */
@Component
public class Pricing {

    private final GatewayProperties properties;

    public Pricing(GatewayProperties properties) {
        this.properties = properties;
    }

    public long cost(String model, long inputTokens, long outputTokens) {
        ModelPrice price = priceOf(model);
        return ceilDiv(inputTokens * price.inputPerMillion(), 1_000_000L)
                + ceilDiv(outputTokens * price.outputPerMillion(), 1_000_000L);
    }

    public boolean supports(String model) {
        return properties.pricing() != null && properties.pricing().models().containsKey(model);
    }

    /**
     * 예약할 최대 비용. 입력 토큰 수는 호출 전에 정확히 알 수 없어서 "글자 1개 = 토큰 1개"로 넉넉하게 잡는다.
     * 넉넉하게 잡은 만큼은 정산 때 돌려준다.
     */
    public long maxCost(String model, long inputChars, int maxOutputTokens) {
        return cost(model, inputChars, maxOutputTokens);
    }

    private ModelPrice priceOf(String model) {
        if (!supports(model)) {
            throw GatewayException.invalidRequest("단가가 등록되지 않은 모델입니다: " + model);
        }
        return properties.pricing().models().get(model);
    }

    private static long ceilDiv(long a, long b) {
        return Math.ceilDiv(a, b);
    }
}
