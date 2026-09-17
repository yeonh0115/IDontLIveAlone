package com.example.smart_door_security_server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private void identity(int owner) {
        var identity = new AppSessionService.SessionIdentity(owner, "hash" + owner, clock.instant().plusSeconds(7200));
        when(app.requireSession("app" + owner)).thenReturn(identity);
        when(app.requireSessionHash("hash" + owner, owner)).thenReturn(identity);
        var device = new PairedDevice(); device.setDeviceId("device" + owner); device.setTokenHash("camera-hash" + owner);
        device.setRole(DeviceRole.CAMERA); device.setUserNo(owner);
        when(registered.findByUserNoAndRole(owner, DeviceRole.CAMERA)).thenReturn(Optional.of(device));
        var camera = new DeviceRegistrationService.DeviceIdentity(device.getDeviceId(), DeviceRole.CAMERA, owner);
        when(devices.require("camera" + owner, DeviceRole.CAMERA, null)).thenReturn(camera);
        when(devices.requireCameraSession(device.getDeviceId(), owner, device.getTokenHash())).thenReturn(camera);
    }
    private void conflict(Runnable action) { expect(HttpStatus.CONFLICT, action); }
    private void limited(Runnable action) { expect(HttpStatus.TOO_MANY_REQUESTS, action); }
    private void expect(HttpStatus status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(status));
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
