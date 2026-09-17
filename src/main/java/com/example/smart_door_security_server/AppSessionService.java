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
    public record SessionIdentity(Integer userNo, String tokenHash, Instant expiresAt) { }

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
        return requireSession(authorization).userNo();
    }

    @Transactional(readOnly = true)
    public SessionIdentity requireSession(String authorization) {
        return requireSessionHash(TokenSecrets.hash(TokenSecrets.bearer(authorization)), null);
    }

    @Transactional(readOnly = true)
    public SessionIdentity requireSessionHash(String tokenHash, Integer expectedOwner) {
        if (tokenHash == null) throw TokenSecrets.unauthorized();
        AppSession session = sessions.findById(tokenHash)
                .orElseThrow(TokenSecrets::unauthorized);
        if (!session.getExpiresAt().isAfter(Instant.now()) || !users.existsById(session.getUserNo())
                || (expectedOwner != null && !expectedOwner.equals(session.getUserNo()))) {
            throw TokenSecrets.unauthorized();
        }
        return new SessionIdentity(session.getUserNo(), session.getTokenHash(), session.getExpiresAt());
    }

    @Transactional
    public void revoke(String authorization) {
        String hash = TokenSecrets.hash(TokenSecrets.bearer(authorization));
        sessions.deleteById(hash);
    }
}
