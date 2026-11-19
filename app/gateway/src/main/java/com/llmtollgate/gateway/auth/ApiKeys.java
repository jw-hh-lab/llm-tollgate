package com.llmtollgate.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API 키 발급과 해시. 키 원문은 발급할 때 한 번만 보여 주고 DB에는 해시만 남긴다.
 */
public final class ApiKeys {

    public static final String PREFIX = "gw_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 로그나 관리 화면에서 키를 구분하는 용도의 앞부분 (예: gw_AbC1234) */
    public static String displayPrefix(String rawKey) {
        return rawKey.substring(0, Math.min(10, rawKey.length()));
    }
}
