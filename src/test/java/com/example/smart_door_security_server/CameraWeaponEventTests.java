package com.example.smart_door_security_server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="EVENT_PHOTOS_ENABLED=false")
class CameraWeaponEventTests {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage-dir", () -> storage.toString());
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:weapon-events;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    }
    @Autowired UserRepository users;
    @Autowired AppSessionService sessions;
    @Autowired DeviceRegistrationService devices;
    @Autowired WriterAccessControl access;
    @Autowired TrustedWriteService writes;
    @Autowired TrustedWriteController controller;
    @Autowired IntegratedLogRepository logs;
    @Autowired DeviceEventReceiptRepository receipts;
    @Autowired CameraCaptureTaskRepository captures;
    @Autowired EventPhotoRepository photos;
    @Autowired DailyReportRepository reports;
    User owner;
    String app, camera;
    MockMvc mvc;

    @BeforeEach void setup() {
        User user = new User();
        user.setUserId("weapon-test-" + UUID.randomUUID());
        owner = users.saveAndFlush(user);
        app = "Bearer " + sessions.issue(owner.getUserNo()).sessionToken();
        camera = pair(DeviceRole.CAMERA);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void cameraHttpEventPersistsExactFieldsAndReplayKeepsOneLogWithoutPhotos() throws Exception {
        long logCount = logs.count(), receiptCount = receipts.count(), captureCount = captures.count(), photoCount = photos.count();
        String payload = """
                {"eventId":"weapon_http","userNo":%d,"occurredAt":"2026-10-01T14:59:59+00:00",
                 "logType":"SECURITY","subType":"흉기감지","val1":0.91,"val2":2.15,
                 "severity":"high","description":"칼이 2.15초 연속 감지됨."}
                """.formatted(owner.getUserNo());
        for (boolean duplicate : new boolean[]{false, true}) {
            mvc.perform(post("/api/device/events").header("Authorization", camera)
                            .contentType("application/json;charset=UTF-8").content(payload))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.userNo").value(owner.getUserNo()))
                    .andExpect(jsonPath("$.duplicate").value(duplicate))
                    .andExpect(jsonPath("$.cameraCaptureQueued").value(false))
                    .andExpect(jsonPath("$.captureStatus").value("NOT_REQUESTED"));
        }
        var receipt = receipts.findByUserNoAndSourceEventId(owner.getUserNo(), "weapon_http").orElseThrow();
        var log = logs.findById(receipt.getLogId()).orElseThrow();
        assertThat(log.getLogType()).isEqualTo(IntegratedLog.LogType.SECURITY);
        assertThat(log.getSubType()).isEqualTo("흉기감지");
        assertThat(log.getSeverity()).isEqualTo(IntegratedLog.Severity.high);
        assertThat(log.getVal1()).isEqualTo(.91f);
        assertThat(log.getVal2()).isEqualTo(2.15f);
        assertThat(log.getDescription()).isEqualTo("칼이 2.15초 연속 감지됨.");
        assertThat(log.getCreatedAt()).isEqualTo(LocalDate.of(2026, 10, 1).atTime(23, 59, 59));
        assertThat(logs.count()).isEqualTo(logCount + 1);
        assertThat(receipts.count()).isEqualTo(receiptCount + 1);
        assertThat(captures.count()).isEqualTo(captureCount);
        assertThat(photos.count()).isEqualTo(photoCount);
        var report = reports.findByUserAndReportDate(owner, receipt.getEventDate()).orElseThrow();
        assertThat(report.getTotalEvents()).isEqualTo(1);
        assertThat(report.getHighRiskEvents()).isEqualTo(1);
        mvc.perform(post("/api/device/events").header("Authorization", camera)
                        .contentType("application/json;charset=UTF-8").content(payload.replace("0.91", "0.99")))
                .andExpect(status().isConflict());
        assertThat(logs.count()).isEqualTo(logCount + 1);
    }

    @Test void cameraCanOnlySubmitTheExactWeaponEventCategory() {
        long before = logs.count();
        for (var type : new IntegratedLog.LogType[]{IntegratedLog.LogType.SENSOR, IntegratedLog.LogType.ENV, null}) {
            forbidden(() -> writes.saveEvent(camera, event("wrong-type", null, type, "흉기감지", IntegratedLog.Severity.high)));
        }
        for (String subtype : new String[]{"door", "흉기감지 ", null}) {
            forbidden(() -> writes.saveEvent(camera, event("wrong-subtype", null, IntegratedLog.LogType.SECURITY, subtype, IntegratedLog.Severity.high)));
        }
        for (var severity : new IntegratedLog.Severity[]{IntegratedLog.Severity.low, IntegratedLog.Severity.medium, null}) {
            forbidden(() -> writes.saveEvent(camera, event("wrong-severity", null, IntegratedLog.LogType.SECURITY, "흉기감지", severity)));
        }
        assertThat(logs.count()).isEqualTo(before);
    }

    @Test void cameraCannotChooseAnotherOwnerAndUnlinkedCameraCannotWrite() {
        long before = logs.count();
        forbidden(() -> writes.saveEvent(camera, weapon("foreign-owner", owner.getUserNo() + 1)));
        var identity = devices.require(camera, DeviceRole.CAMERA, null);
        devices.unlink(app, identity.deviceId());
        forbidden(() -> writes.saveEvent(camera, weapon("unlinked", owner.getUserNo())));
        assertThat(logs.count()).isEqualTo(before);
    }

    @Test void pendingCameraCannotWriteBeforeAppPairing() {
        String token = "Bearer " + TokenSecrets.generate();
        devices.enroll(token, new DeviceRegistrationService.EnrollRequest(DeviceRole.CAMERA, "pending"), UUID.randomUUID().toString());
        forbidden(() -> writes.saveEvent(token, weapon("pending", owner.getUserNo())));
    }

    @Test void sensorAndReportPathsRemainSeparateFromCameraEventPermission() {
        String sensor = pair(DeviceRole.SENSOR), report = pair(DeviceRole.REPORT);
        var saved = writes.saveEvent(sensor, event("sensor-original", null, IntegratedLog.LogType.SENSOR, "door", IntegratedLog.Severity.medium));
        assertThat(saved.userNo()).isEqualTo(owner.getUserNo());
        var summary = writes.saveReport(report, new TrustedWriteService.ReportRequest("report-original", null, saved.eventDate(), "summary"));
        assertThat(summary.userNo()).isEqualTo(owner.getUserNo());
        forbidden(() -> writes.saveEvent(report, weapon("report-role", null)));
        forbidden(() -> writes.saveReport(camera, new TrustedWriteService.ReportRequest("camera-report", null, saved.eventDate(), "summary")));
        forbidden(() -> access.resolveDevice(camera, owner.getUserNo()));
    }

    @Test void simultaneousCameraRetriesProduceOneLogAndUseThePairedOwner() throws Exception {
        var request = weapon("camera-parallel", null);
        long before = logs.count();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return writes.saveEvent(camera, request); });
            var b = pool.submit(() -> { start.await(); return writes.saveEvent(camera, request); });
            start.countDown();
            var first = a.get(15, TimeUnit.SECONDS);
            var second = b.get(15, TimeUnit.SECONDS);
            assertThat(first.userNo()).isEqualTo(owner.getUserNo());
            assertThat(first.logId()).isEqualTo(second.logId());
            assertThat(first.duplicate()).isNotEqualTo(second.duplicate());
        }
        assertThat(logs.count()).isEqualTo(before + 1);
    }

    private String pair(DeviceRole role) {
        String token = "Bearer " + TokenSecrets.generate();
        var pending = devices.enroll(token, new DeviceRegistrationService.EnrollRequest(role, role.name()), UUID.randomUUID().toString());
        devices.claim(app, pending.pairingCode(), UUID.randomUUID().toString());
        return token;
    }
    private static TrustedWriteService.EventRequest weapon(String id, Integer owner) {
        return event(id, owner, IntegratedLog.LogType.SECURITY, "흉기감지", IntegratedLog.Severity.high);
    }
    private static TrustedWriteService.EventRequest event(String id, Integer owner, IntegratedLog.LogType type,
            String subtype, IntegratedLog.Severity severity) {
        return new TrustedWriteService.EventRequest(id, owner, OffsetDateTime.parse("2026-10-01T14:59:59Z"),
                type, subtype, .91f, 2.15f, severity, "칼이 2.15초 연속 감지됨.");
    }
    private static void forbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
