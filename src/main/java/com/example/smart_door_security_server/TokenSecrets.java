package com.example.smart_door_security_server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class TokenSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private TokenSecrets() { }
    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw unauthorized();
        String token = authorization.substring(7).strip();
        if (!token.matches("[!-~]{32,256}")) throw unauthorized();
        return token;
    }
    static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 정보가 없거나 만료되었습니다.");
    }
}
