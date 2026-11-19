package com.llmtollgate.gateway.auth;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiKeyRepository {

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findActiveCustomerId(String keyHash) {
        return jdbc.sql("SELECT customer_id FROM api_key WHERE key_hash = ? AND status = 'ACTIVE'")
                .param(keyHash)
                .query(Long.class)
                .optional();
    }

    public void insert(long customerId, String keyHash, String keyPrefix) {
        jdbc.sql("INSERT INTO api_key (customer_id, key_hash, key_prefix, status) VALUES (?, ?, ?, 'ACTIVE')")
                .params(customerId, keyHash, keyPrefix)
                .update();
    }
}
