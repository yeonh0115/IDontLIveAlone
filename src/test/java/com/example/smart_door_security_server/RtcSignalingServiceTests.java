package com.example.smart_door_security_server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RtcSignalingServiceTests {
    final AppSessionService app = mock(AppSessionService.class);
    final DeviceRegistrationService devices = mock(DeviceRegistrationService.class);
    final PairedDeviceRepository registered = mock(PairedDeviceRepository.class);
    final MutableClock clock = new MutableClock();
    RtcSignalingService signaling;
    final RtcSignalingService.SdpRequest offer = new RtcSignalingService.SdpRequest("offer", "v=0\r\no=offer");
    final RtcSignalingService.SdpRequest answer = new RtcSignalingService.SdpRequest("answer", "v=0\r\no=answer");
    @BeforeEach void setup() { signaling = new RtcSignalingService(app, devices, registered, clock); identity(1); }

    @Test void viewerLeaseExpiresAndCameraPollingDoesNotKeepAnAbandonedSessionAlive() {
        var call = signaling.create("app1", offer);
        clock.advance(40); assertThat(signaling.next("camera1")).isPresent();
        clock.advance(5);
        assertThat(signaling.cameraStatus("camera1", call.sessionId()).status()).isEqualTo("EXPIRED");
        assertThat(signaling.next("camera1")).isEmpty();
        conflict(() -> signaling.answer("camera1", call.sessionId(), answer));
    }

    @Test void pendingAndReadyDeadlinesRemainBoundedDespiteViewerRenewal() {
        var pending = signaling.create("app1", offer);
        clock.advance(40); assertThat(signaling.viewerStatus("app1", pending.sessionId()).expiresAt()).isEqualTo(clock.instant().plusSeconds(45));
        clock.advance(40); assertThat(signaling.viewerStatus("app1", pending.sessionId()).expiresAt()).isEqualTo(Instant.parse("2026-09-17T00:01:30Z"));
        clock.advance(10); assertThat(signaling.viewerStatus("app1", pending.sessionId()).status()).isEqualTo("EXPIRED");
        var ready = signaling.create("app1", offer); signaling.answer("camera1", ready.sessionId(), answer);
        for (int i = 0; i < 179; i++) { clock.advance(10); assertThat(signaling.viewerStatus("app1", ready.sessionId()).status()).isEqualTo("READY"); }
        clock.advance(10); var expired = signaling.viewerStatus("app1", ready.sessionId());
        assertThat(expired.status()).isEqualTo("EXPIRED"); assertThat(expired.answerSdp()).isNull();
    }

    @Test void sameAnswerIsIdempotentButReplacementAndConflictingAnswersAreRejected() {
        var first = signaling.create("app1", offer);
        assertThat(signaling.next("camera1").orElseThrow().sessionId()).isEqualTo(first.sessionId());
        assertThat(signaling.next("camera1").orElseThrow().sessionId()).isEqualTo(first.sessionId());
        signaling.answer("camera1", first.sessionId(), answer); signaling.answer("camera1", first.sessionId(), answer);
        assertThat(signaling.viewerStatus("app1", first.sessionId()).answerSdp()).isEqualTo(answer.sdp());
        conflict(() -> signaling.answer("camera1", first.sessionId(), new RtcSignalingService.SdpRequest("answer", "changed")));
        var second = signaling.create("app1", offer);
        var closed = signaling.cameraStatus("camera1", first.sessionId());
        assertThat(closed.status()).isEqualTo("CLOSED"); assertThat(closed.answerSdp()).isNull();
        assertThat(signaling.next("camera1").orElseThrow().sessionId()).isEqualTo(second.sessionId());
        signaling.close("app1", first.sessionId()); signaling.close("app1", first.sessionId());
    }

    @Test void activeCapacityIsBoundedAndTerminalSlotsCanBeReused() {
        List<RtcSignalingService.Status> calls = new ArrayList<>();
        for (int i = 1; i <= 65; i++) identity(i);
        for (int i = 1; i <= 64; i++) calls.add(signaling.create("app" + i, offer));
        limited(() -> signaling.create("app65", offer));
        signaling.close("app1", calls.getFirst().sessionId());
        assertThat(signaling.create("app65", offer).status()).isEqualTo("PENDING");
        clock.advance(46);
        assertThat(signaling.create("app1", offer).status()).isEqualTo("PENDING");
    }

    @Test void offerAndReadRatesAreBoundedAndRecoverInTheNextWindow() {
        for (int i = 0; i < 10; i++) signaling.create("app1", offer);
        limited(() -> signaling.create("app1", offer));
        clock.advance(60); var call = signaling.create("app1", offer);
        for (int i = 0; i < 119; i++) signaling.viewerStatus("app1", call.sessionId());
        limited(() -> signaling.viewerStatus("app1", call.sessionId()));
        clock.advance(60); assertThat(signaling.create("app1", offer).status()).isEqualTo("PENDING");
    }

    @Test void concurrentNewOffersLeaveExactlyOnePendingSessionForTheCamera() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            Callable<RtcSignalingService.Status> create = () -> { gate.await(); return signaling.create("app1", offer); };
            var first = executor.submit(create); var second = executor.submit(create); gate.countDown();
            var one = first.get(3, TimeUnit.SECONDS); var two = second.get(3, TimeUnit.SECONDS);
            assertThat(List.of(signaling.viewerStatus("app1", one.sessionId()).status(),
                    signaling.viewerStatus("app1", two.sessionId()).status())).containsExactlyInAnyOrder("PENDING", "CLOSED");
            assertThat(signaling.next("camera1").orElseThrow().sessionId()).isIn(one.sessionId(), two.sessionId());
        } finally { executor.shutdownNow(); }
    }

    @Test void utf8ByteLimitIsEnforcedAndARevokedCreatorRemovesPrivateAnswerData() {
        expect(HttpStatus.BAD_REQUEST, () -> signaling.create("app1", new RtcSignalingService.SdpRequest("offer", "가".repeat(22000))));
        var call = signaling.create("app1", offer); signaling.answer("camera1", call.sessionId(), answer);
        when(app.requireSessionHash("hash1", 1)).thenThrow(TokenSecrets.unauthorized());
        var result = signaling.cameraStatus("camera1", call.sessionId());
        assertThat(result.status()).isEqualTo("CLOSED"); assertThat(result.answerSdp()).isNull();
    }

    @Test void slowCounterpartLookupDoesNotBlockAnIndependentCallerOrExpiryCleanup() throws Exception {
        identity(2);
        var one = signaling.create("app1", offer); var two = signaling.create("app2", offer);
        signaling.answer("camera1", one.sessionId(), answer);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(devices.requireCameraSession("device1", 1, "camera-hash1")).thenAnswer(invocation -> {
            assertThat(Thread.holdsLock(signaling)).as("DB validation must not hold the global state lock").isFalse();
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new DeviceRegistrationService.DeviceIdentity("device1", DeviceRole.CAMERA, 1);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var slow = executor.submit(() -> signaling.viewerStatus("app1", one.sessionId()));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var independent = executor.submit(() -> signaling.viewerStatus("app2", two.sessionId()));
            assertThat(independent.get(2, TimeUnit.SECONDS).status()).isEqualTo("PENDING");
            clock.advance(46);
            executor.submit(signaling::expireIdleSessions).get(2, TimeUnit.SECONDS);
            release.countDown();
            var result = slow.get(2, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo("EXPIRED");
            assertThat(result.answerSdp()).isNull();
            assertThat(result.expiresAt()).isBefore(clock.instant());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(strings={"CLOSE", "REPLACE", "EXPIRE", "EVICT"})
    void slowNextNeverPublishesAnOfferAfterItsSnapshotWasClosedReplacedOrExpired(String transition) throws Exception {
        var original = signaling.create("app1", offer);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        blockCreator(entered, release);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var slow = executor.submit(() -> signaling.next("camera1"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> transition(transition, original.sessionId())).get(2, TimeUnit.SECONDS);
            release.countDown();
            assertThat(slow.get(2, TimeUnit.SECONDS)).isEmpty();
            if (transition.equals("REPLACE")) {
                assertThat(signaling.next("camera1").orElseThrow().sessionId()).isNotEqualTo(original.sessionId());
            }
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(strings={"CLOSE", "REPLACE", "EXPIRE", "EVICT"})
    void slowAnswerCannotReviveAClosedReplacedExpiredOrEvictedCall(String transition) throws Exception {
        var original = signaling.create("app1", offer);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        blockCreator(entered, release);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var slow = executor.submit(() -> signaling.answer("camera1", original.sessionId(), answer));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> transition(transition, original.sessionId())).get(2, TimeUnit.SECONDS);
            release.countDown();
            assertThatThrownBy(() -> slow.get(2, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                    failure -> assertThat(failure.getCause()).isInstanceOfSatisfying(ResponseStatusException.class,
                            response -> assertThat(response.getStatusCode()).isEqualTo(
                                    transition.equals("EVICT") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT)));
            if (!transition.equals("EVICT")) {
                var state = signaling.viewerStatus("app1", original.sessionId());
                assertThat(state.status()).isIn("CLOSED", "EXPIRED"); assertThat(state.answerSdp()).isNull();
            }
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void racingAnswerValidationPreservesIdempotenceAndNeverOverwritesAConflictingAnswer(boolean identical) throws Exception {
        var original = signaling.create("app1", offer);
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        blockCreator(entered, release);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var other = identical ? answer : new RtcSignalingService.SdpRequest("answer", "v=0\r\no=other-answer");
            var first = executor.submit(() -> answerOutcome(original.sessionId(), answer));
            var second = executor.submit(() -> answerOutcome(original.sessionId(), other));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); release.countDown();
            var outcomes = List.of(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
            if (identical) assertThat(outcomes).containsExactly(204, 204);
            else assertThat(outcomes).containsExactlyInAnyOrder(204, 409);
            assertThat(signaling.viewerStatus("app1", original.sessionId()).answerSdp()).isIn(answer.sdp(), other.sdp());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void readyNextSkipsCreatorReadsButStillChecksTheCurrentCameraCredentialAndBinding() {
        var call = signaling.create("app1", offer); signaling.answer("camera1", call.sessionId(), answer);
        clearInvocations(app, devices);
        assertThat(signaling.next("camera1")).isEmpty();
        verify(devices).requireCameraCredential("camera1"); verifyNoInteractions(app);
        when(devices.requireCameraCredential("camera1")).thenReturn(
                new DeviceRegistrationService.CameraCredential("device1", 1, "rekeyed-hash"));
        assertThat(signaling.next("camera1")).isEmpty();
        when(devices.requireCameraSession("device1", 1, "camera-hash1")).thenThrow(TokenSecrets.unauthorized());
        assertThat(signaling.viewerStatus("app1", call.sessionId()).status()).isEqualTo("CLOSED");
        when(devices.requireCameraCredential("camera1")).thenThrow(TokenSecrets.unauthorized());
        expect(HttpStatus.UNAUTHORIZED, () -> signaling.next("camera1"));
    }

    @Test void aDelayedCameraAuthenticationCannotCloseANewOwnersCallForTheSamePhysicalDevice() throws Exception {
        signaling.create("app1", offer);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(devices.requireCameraCredential("camera1")).thenAnswer(invocation -> {
            assertThat(Thread.holdsLock(signaling)).isFalse();
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new DeviceRegistrationService.CameraCredential("device1", 1, "camera-hash1");
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var slow = executor.submit(() -> signaling.next("camera1"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            identity(2);
            var reassigned = new PairedDevice(); reassigned.setDeviceId("device1");
            reassigned.setUserNo(2); reassigned.setRole(DeviceRole.CAMERA); reassigned.setTokenHash("camera-hash2");
            when(registered.findByUserNoAndRole(2, DeviceRole.CAMERA)).thenReturn(Optional.of(reassigned));
            when(devices.requireCameraCredential("camera2")).thenReturn(
                    new DeviceRegistrationService.CameraCredential("device1", 2, "camera-hash2"));
            var replacement = executor.submit(() -> signaling.create("app2", offer)).get(2, TimeUnit.SECONDS);
            release.countDown(); assertThat(slow.get(2, TimeUnit.SECONDS)).isEmpty();
            assertThat(signaling.next("camera2").orElseThrow().sessionId()).isEqualTo(replacement.sessionId());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private int answerOutcome(String id, RtcSignalingService.SdpRequest request) {
        try { signaling.answer("camera1", id, request); return 204; }
        catch (ResponseStatusException conflict) { return conflict.getStatusCode().value(); }
    }
    private void blockCreator(CountDownLatch entered, CountDownLatch release) {
        when(app.requireSessionHash("hash1", 1)).thenAnswer(invocation -> {
            assertThat(Thread.holdsLock(signaling)).as("DB validation must not hold the global state lock").isFalse();
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new AppSessionService.SessionIdentity(1, "hash1", Instant.parse("2026-09-17T02:00:00Z"));
        });
    }
    private void transition(String transition, String id) {
        switch (transition) {
            case "CLOSE" -> signaling.close("app1", id);
            case "REPLACE" -> signaling.create("app1", offer);
            case "EXPIRE" -> { clock.advance(46); signaling.expireIdleSessions(); }
            case "EVICT" -> { clock.advance(166); signaling.expireIdleSessions(); }
            default -> throw new AssertionError(transition);
        }
    }

    private void identity(int owner) {
        var identity = new AppSessionService.SessionIdentity(owner, "hash" + owner, clock.instant().plusSeconds(7200));
        when(app.requireSession("app" + owner)).thenReturn(identity);
        when(app.requireSessionHash("hash" + owner, owner)).thenReturn(identity);
        var device = new PairedDevice(); device.setDeviceId("device" + owner); device.setTokenHash("camera-hash" + owner);
        device.setRole(DeviceRole.CAMERA); device.setUserNo(owner);
        when(registered.findByUserNoAndRole(owner, DeviceRole.CAMERA)).thenReturn(Optional.of(device));
        var camera = new DeviceRegistrationService.DeviceIdentity(device.getDeviceId(), DeviceRole.CAMERA, owner);
        when(devices.require("camera" + owner, DeviceRole.CAMERA, null)).thenReturn(camera);
        when(devices.requireCameraCredential("camera" + owner)).thenReturn(
                new DeviceRegistrationService.CameraCredential(device.getDeviceId(), owner, device.getTokenHash()));
        when(devices.requireCameraSession(device.getDeviceId(), owner, device.getTokenHash())).thenReturn(camera);
    }
    private void conflict(Runnable action) { expect(HttpStatus.CONFLICT, action); }
    private void limited(Runnable action) { expect(HttpStatus.TOO_MANY_REQUESTS, action); }
    private void expect(HttpStatus status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(status));
    }
    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-09-17T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
