package com.llmtollgate.gateway.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.llmtollgate.gateway.auth.ApiKeyRepository;
import com.llmtollgate.gateway.auth.ApiKeys;
import com.llmtollgate.gateway.common.GatewayException;
import com.llmtollgate.gateway.credit.CreditService;

@Service
public class CustomerService {

    private final JdbcClient jdbc;
    private final ApiKeyRepository apiKeyRepository;
    private final CreditService creditService;

    public CustomerService(JdbcClient jdbc, ApiKeyRepository apiKeyRepository, CreditService creditService) {
        this.jdbc = jdbc;
        this.apiKeyRepository = apiKeyRepository;
        this.creditService = creditService;
    }

    /** 고객을 만들고 API 키를 발급한다. 키 원문은 이 응답에서만 볼 수 있다. */
    @Transactional
    public CreatedCustomer create(String name, long initialCredits) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO customer (name) VALUES (?)").param(name).update(keyHolder, "id");
        long customerId = keyHolder.getKey().longValue();

        creditService.openAccount(customerId);
        long balance = initialCredits > 0 ? creditService.charge(customerId, initialCredits) : 0;
        String rawKey = issueKey(customerId);
        return new CreatedCustomer(customerId, rawKey, balance);
    }

    @Transactional
    public String issueKey(long customerId) {
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM customer WHERE id = ?")
                .param(customerId).query(Long.class).single() > 0;
        if (!exists) {
            throw GatewayException.notFound("고객을 찾을 수 없습니다.");
        }
        String rawKey = ApiKeys.generate();
        apiKeyRepository.insert(customerId, ApiKeys.hash(rawKey), ApiKeys.displayPrefix(rawKey));
        return rawKey;
    }

    public record CreatedCustomer(long customerId, String apiKey, long credits) {
    }
}
