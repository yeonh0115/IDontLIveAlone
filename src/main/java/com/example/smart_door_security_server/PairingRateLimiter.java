package com.example.smart_door_security_server;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Instant;
import java.time.Duration;

@Service
public class PairingRateLimiter {
    private final PairingRateBucketRepository buckets;
    private final TransactionTemplate transaction;
    public PairingRateLimiter(PairingRateBucketRepository buckets, PlatformTransactionManager manager) {
        this.buckets = buckets;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    // Each attempt commits separately, including invalid/expired codes whose caller rolls back.
    public synchronized void consume(String scope, int limit) {
        String key = TokenSecrets.hash(scope);
        Boolean accepted;
        try { accepted = increment(key, limit); }
        catch (DataIntegrityViolationException concurrentCreation) { accepted = increment(key, limit); }
        if (!Boolean.TRUE.equals(accepted)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "연결 시도가 너무 많습니다. 10분 후 다시 시도해 주세요.");
        }
    }
    private Boolean increment(String key, int limit) {
        return transaction.execute(status -> {
            Instant now = Instant.now();
            PairingRateBucket bucket = buckets.findForUpdate(key).orElseGet(() -> {
                PairingRateBucket created = new PairingRateBucket();
                created.setBucketKey(key); created.setWindowStart(now); return created;
            });
            if (!bucket.getWindowStart().plus(Duration.ofMinutes(10)).isAfter(now)) {
                bucket.setWindowStart(now); bucket.setAttempts(0);
            }
            if (bucket.getAttempts() >= limit) return false;
            bucket.setAttempts(bucket.getAttempts() + 1);
            buckets.saveAndFlush(bucket);
            return true;
        });
    }
}
