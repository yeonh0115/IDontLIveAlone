package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.Duration;

@Service @RequiredArgsConstructor
public class AppSessionService {
    private final AppSessionRepository sessions;
    private final UserRepository users;
    public record IssuedSession(String sessionToken, Instant sessionExpiresAt) { }

    @Transactional
    public IssuedSession issue(Integer userNo) {
        String token = TokenSecrets.generate();
        AppSession session = new AppSession();
        session.setTokenHash(TokenSecrets.hash(token));
        session.setUserNo(userNo);
        session.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        sessions.save(session);
        return new IssuedSession(token, session.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public Integer requireUser(String authorization) {
        AppSession session = sessions.findById(TokenSecrets.hash(TokenSecrets.bearer(authorization)))
                .orElseThrow(TokenSecrets::unauthorized);
        if (!session.getExpiresAt().isAfter(Instant.now()) || !users.existsById(session.getUserNo())) {
            throw TokenSecrets.unauthorized();
        }
        return session.getUserNo();
    }

    @Transactional
    public void revoke(String authorization) {
        String hash = TokenSecrets.hash(TokenSecrets.bearer(authorization));
        sessions.deleteById(hash);
    }
}
