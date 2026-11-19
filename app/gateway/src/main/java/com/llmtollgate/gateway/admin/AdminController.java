package com.llmtollgate.gateway.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.llmtollgate.gateway.common.GatewayException;
import com.llmtollgate.gateway.config.GatewayProperties;
import com.llmtollgate.gateway.credit.CreditService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 운영자용 API: 고객 등록, 키 발급, 크레딧 충전.
 * X-Admin-Token 헤더가 ADMIN_TOKEN 환경 변수와 같아야 한다. 값이 비어 있으면 전부 막는다.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final GatewayProperties properties;
    private final CustomerService customerService;
    private final CreditService creditService;

    public AdminController(GatewayProperties properties, CustomerService customerService,
                           CreditService creditService) {
        this.properties = properties;
        this.customerService = customerService;
        this.creditService = creditService;
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerService.CreatedCustomer createCustomer(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                                          @Valid @RequestBody CreateCustomerRequest request) {
        checkAdmin(token);
        return customerService.create(request.name(), request.initialCredits() == null ? 0 : request.initialCredits());
    }

    @PostMapping("/customers/{customerId}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyResponse issueKey(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                   @PathVariable long customerId) {
        checkAdmin(token);
        return new ApiKeyResponse(customerId, customerService.issueKey(customerId));
    }

    @PostMapping("/customers/{customerId}/credits")
    public BalanceResponse charge(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                  @PathVariable long customerId,
                                  @Valid @RequestBody ChargeRequest request) {
        checkAdmin(token);
        return new BalanceResponse(customerId, creditService.charge(customerId, request.amount()));
    }

    private void checkAdmin(String token) {
        String expected = properties.adminToken();
        boolean ok = expected != null && !expected.isBlank() && token != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "forbidden", "관리자 토큰이 올바르지 않습니다.");
        }
    }

    public record CreateCustomerRequest(@NotBlank String name, @PositiveOrZero Long initialCredits) {
    }

    public record ChargeRequest(@Positive long amount) {
    }

    public record ApiKeyResponse(long customerId, String apiKey) {
    }

    public record BalanceResponse(long customerId, long balance) {
    }
}
