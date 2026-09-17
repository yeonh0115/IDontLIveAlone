package com.example.smart_door_security_server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Short-lived signaling only. Media and SDP are never persisted or logged. */
@Service
public class RtcSignalingService {
    static final int MAX_SESSIONS = 64;
    static final int MAX_SDP_BYTES = 64 * 1024;
    static final Duration VIEWER_LEASE = Duration.ofSeconds(45);
    static final Duration PENDING_LIFETIME = Duration.ofSeconds(90);
    static final Duration READY_LIFETIME = Duration.ofMinutes(30);
    private static final Duration CLOSED_RETENTION = Duration.ofMinutes(2);
    private static final int MAX_RATE_WINDOWS = 256;
    private final AppSessionService appSessions;
    private final DeviceRegistrationService devices;
    private final PairedDeviceRepository registered;
    private final Clock clock;
    private final Map<String, Call> calls = new LinkedHashMap<>();
    private final Map<String, RateWindow> rates = new HashMap<>();

    @Autowired
    public RtcSignalingService(AppSessionService appSessions, DeviceRegistrationService devices,
                               PairedDeviceRepository registered) {
        this(appSessions, devices, registered, Clock.systemUTC());
    }
    RtcSignalingService(AppSessionService appSessions, DeviceRegistrationService devices,
                        PairedDeviceRepository registered, Clock clock) {
        this.appSessions = appSessions; this.devices = devices; this.registered = registered; this.clock = clock;
    }
    public record SdpRequest(String type, String sdp) { }
    public record Status(String sessionId, String status, String answerType, String answerSdp, Instant expiresAt) { }
    public record Offer(String sessionId, String type, String sdp, Instant expiresAt) { }

    @Scheduled(fixedDelay=10000, initialDelay=10000)
    public synchronized void expireIdleSessions() {
        // Also discard private SDP after abandoned callers stop sending requests.
        cleanup(clock.instant());
    }

    public Status create(String authorization, SdpRequest request) {
        var app = appSessions.requireSession(authorization);
        validateSdp(request, "offer");
        var camera = registered.findByUserNoAndRole(app.userNo(), DeviceRole.CAMERA)
                .orElseThrow(RtcSignalingService::missing);
        // The owner was just authenticated, and this query already restricts owner and CAMERA role.
        // Capture its credential once rather than reading the same device/user again.
        synchronized (this) {
            Instant now = clock.instant(); cleanup(now);
            consumeRate("offer:" + app.userNo(), 10, now);
            consumeRate("app:" + app.tokenHash(), 120, now);
            // Replacement is atomic. Capacity failure must leave an existing call intact.
            Call previous = calls.values().stream().filter(c -> c.cameraId.equals(camera.getDeviceId()) && c.active())
                    .findFirst().orElse(null);
            if (calls.size() >= MAX_SESSIONS && previous == null && calls.values().stream().allMatch(Call::active)) {
                throw limited();
            }
            if (previous != null) previous.end("CLOSED", now);
            while (calls.size() >= MAX_SESSIONS) {
                String terminal = calls.values().stream().filter(c -> !c.active()).map(c -> c.id).findFirst().orElseThrow();
                calls.remove(terminal);
            }
            Call call = new Call(UUID.randomUUID().toString(), app.userNo(), app.tokenHash(), app.expiresAt(),
                    camera.getDeviceId(), camera.getTokenHash(), request.sdp(), now);
            calls.put(call.id, call);
            return call.view();
        }
    }

    public Status viewerStatus(String authorization, String id) {
        var app = appSessions.requireSession(authorization);
        Call snapshot;
        synchronized (this) {
            Instant now = clock.instant(); cleanup(now); consumeRate("app:" + app.tokenHash(), 120, now);
            snapshot = viewerCall(id, app);
            if (!snapshot.active()) return snapshot.view();
        }
        // Immutable call identities are safe to validate outside the state lock. The caller's
        // app identity was already checked above; only the counterpart needs a DB read.
        boolean cameraValid = cameraIsValid(snapshot);
        synchronized (this) {
            Instant now = clock.instant(); resume(snapshot, now);
            applyValidation(snapshot, cameraValid && app.expiresAt().isAfter(now), now);
            if (snapshot.active()) snapshot.viewerUntil = now.plus(VIEWER_LEASE);
            return snapshot.view();
        }
    }

    public void close(String authorization, String id) {
        var app = appSessions.requireSession(authorization);
        synchronized (this) {
            Instant now = clock.instant(); cleanup(now); consumeRate("app:" + app.tokenHash(), 120, now);
            Call call = viewerCall(id, app);
            if (call.active()) call.end("CLOSED", now);
        }
    }

    public Optional<Offer> next(String authorization) {
        var camera = devices.requireCameraCredential(authorization);
        Call snapshot = null;
        synchronized (this) {
            Instant now = clock.instant(); cleanup(now); consumeRate("camera:" + camera.deviceId(), 120, now);
            for (Call call : calls.values()) {
                if (!call.cameraId.equals(camera.deviceId()) || !call.active()) continue;
                // This caller may have authenticated before a re-pair/rekey and before the
                // new call was created. A mismatching snapshot must not close that newer call.
                if (!sameCamera(call, camera)) return Optional.empty();
                // READY has no pending offer to disclose. Its status endpoint still validates
                // both peers on every access; polling next need only authenticate its caller.
                if (!call.status.equals("PENDING")) return Optional.empty();
                snapshot = call; break;
            }
            if (snapshot == null) return Optional.empty();
        }
        var creator = validatedCreator(snapshot);
        synchronized (this) {
            Instant now = clock.instant(); cleanup(now);
            // An answer, close, replacement, expiration or eviction may have won while the
            // DB was busy. Never publish an offer copied before that state transition.
            if (calls.get(snapshot.id) != snapshot) return Optional.empty();
            applyValidation(snapshot, creator != null && creator.expiresAt().isAfter(now), now);
            if (!snapshot.status.equals("PENDING")) return Optional.empty();
            return Optional.of(new Offer(snapshot.id, "offer", snapshot.offer, snapshot.expiresAt()));
        }
    }

    public Status cameraStatus(String authorization, String id) {
        var camera = devices.requireCameraCredential(authorization);
        Call snapshot;
        synchronized (this) {
            Instant now = clock.instant(); cleanup(now); consumeRate("camera:" + camera.deviceId(), 120, now);
            snapshot = cameraCall(id, camera);
            applyValidation(snapshot, sameCamera(snapshot, camera), now);
            if (!snapshot.active()) return snapshot.view();
        }
        var creator = validatedCreator(snapshot);
        synchronized (this) {
            Instant now = clock.instant(); resume(snapshot, now);
            applyValidation(snapshot, creator != null && creator.expiresAt().isAfter(now), now);
            return snapshot.view();
        }
    }

    public void answer(String authorization, String id, SdpRequest request) {
        var camera = devices.requireCameraCredential(authorization);
        validateSdp(request, "answer");
        Call snapshot;
        synchronized (this) {
            Instant now = clock.instant(); cleanup(now); consumeRate("camera:" + camera.deviceId(), 120, now);
            snapshot = cameraCall(id, camera);
            applyValidation(snapshot, sameCamera(snapshot, camera), now);
            if (!snapshot.active()) throw conflict();
        }
        var creator = validatedCreator(snapshot);
        synchronized (this) {
            Instant now = clock.instant(); resume(snapshot, now);
            applyValidation(snapshot, creator != null && creator.expiresAt().isAfter(now), now);
            if (snapshot.status.equals("READY") && request.sdp().equals(snapshot.answer)) return;
            if (!snapshot.status.equals("PENDING")) throw conflict();
            snapshot.answer = request.sdp(); snapshot.offer = null; snapshot.status = "READY";
            snapshot.hardUntil = now.plus(READY_LIFETIME);
        }
    }

    private Call viewerCall(String id, AppSessionService.SessionIdentity app) {
        Call call = calls.get(id);
        if (call == null || !call.owner.equals(app.userNo()) || !call.creatorHash.equals(app.tokenHash())) throw missing();
        return call;
    }
    private Call cameraCall(String id, DeviceRegistrationService.CameraCredential camera) {
        Call call = calls.get(id);
        if (call == null || !call.cameraId.equals(camera.deviceId()) || !call.owner.equals(camera.userNo())) throw missing();
        return call;
    }
    private boolean sameCamera(Call call, DeviceRegistrationService.CameraCredential camera) {
        return call.owner.equals(camera.userNo()) && call.cameraId.equals(camera.deviceId())
                && call.cameraHash.equals(camera.tokenHash());
    }
    private boolean cameraIsValid(Call call) {
        try {
            devices.requireCameraSession(call.cameraId, call.owner, call.cameraHash);
            return true;
        } catch (ResponseStatusException revoked) {
            requireAuthFailure(revoked); return false;
        }
    }
    private AppSessionService.SessionIdentity validatedCreator(Call call) {
        try { return appSessions.requireSessionHash(call.creatorHash, call.owner); }
        catch (ResponseStatusException revoked) { requireAuthFailure(revoked); return null; }
    }
    private static void requireAuthFailure(ResponseStatusException failure) {
        int status = failure.getStatusCode().value();
        if (status != 401 && status != 403 && status != 404) throw failure;
    }
    /** Called under the lock after DB reads, using current time and current mutable state. */
    private void resume(Call snapshot, Instant now) {
        cleanup(now);
        if (calls.get(snapshot.id) != snapshot) throw missing();
    }
    private void applyValidation(Call call, boolean valid, Instant now) {
        if (call.active() && !valid) call.end("CLOSED", now);
    }
    private void cleanup(Instant now) {
        for (Call call : calls.values()) {
            if (call.active() && !call.expiresAt().isAfter(now)) call.end("EXPIRED", call.expiresAt());
        }
        calls.values().removeIf(c -> !c.active() && !c.endedAt.plus(CLOSED_RETENTION).isAfter(now));
        rates.values().removeIf(r -> !r.until.isAfter(now));
    }
    private void consumeRate(String key, int maximum, Instant now) {
        RateWindow window = rates.get(key);
        if (window == null || !window.until.isAfter(now)) {
            if (window == null && rates.size() >= MAX_RATE_WINDOWS) throw limited();
            window = new RateWindow(now.plusSeconds(60)); rates.put(key, window);
        }
        if (window.used >= maximum) throw limited();
        window.used++;
    }
    private static void validateSdp(SdpRequest request, String type) {
        if (request == null || !type.equals(request.type()) || request.sdp() == null || request.sdp().isBlank()
                || request.sdp().length() > MAX_SDP_BYTES || request.sdp().getBytes(StandardCharsets.UTF_8).length > MAX_SDP_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 종류의 64KiB 이하 SDP가 필요합니다.");
        }
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "영상 연결을 찾을 수 없습니다."); }
    private static ResponseStatusException limited() { return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 영상 연결을 다시 시도해 주세요."); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "영상 연결 상태가 변경되었습니다."); }

    private static final class RateWindow {
        final Instant until; int used;
        RateWindow(Instant until) { this.until = until; }
    }
    private static final class Call {
        final String id, creatorHash, cameraId, cameraHash;
        final Integer owner;
        final Instant creatorExpiresAt;
        String offer, answer, status = "PENDING";
        Instant hardUntil, viewerUntil, endedAt;
        Call(String id, Integer owner, String creatorHash, Instant creatorExpiresAt, String cameraId,
             String cameraHash, String offer, Instant now) {
            this.id = id; this.owner = owner; this.creatorHash = creatorHash; this.creatorExpiresAt = creatorExpiresAt;
            this.cameraId = cameraId; this.cameraHash = cameraHash; this.offer = offer;
            hardUntil = now.plus(PENDING_LIFETIME); viewerUntil = now.plus(VIEWER_LEASE);
        }
        boolean active() { return status.equals("PENDING") || status.equals("READY"); }
        Instant expiresAt() {
            if (!active()) return endedAt;
            Instant deadline = hardUntil.isBefore(viewerUntil) ? hardUntil : viewerUntil;
            return deadline.isBefore(creatorExpiresAt) ? deadline : creatorExpiresAt;
        }
        void end(String status, Instant when) { this.status = status; endedAt = when; offer = null; answer = null; }
        Status view() { return new Status(id, status, status.equals("READY") ? "answer" : null,
                status.equals("READY") ? answer : null, expiresAt()); }
    }
}
