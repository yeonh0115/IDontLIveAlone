package com.example.smart_door_security_server;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DevicePairingTests {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("app.storage-dir", () -> storage.toString());
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:pairing-tests;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    }
    @Autowired DeviceRegistrationService devices;
    @Autowired AppSessionService sessions;
    @Autowired PairedDeviceRepository registered;
    @Autowired AppSessionRepository sessionRows;
    @Autowired PairingRateBucketRepository buckets;
    @Autowired UserRepository users;
    @Autowired CameraCaptureService captures;
    @Autowired CameraCaptureTaskRepository tasks;
    @Autowired CameraPhotoService cameraPhotos;
    @Autowired TrustedWriteService writes;
    @Autowired TaskQueueService faces;
    @Autowired FaceImageStorage faceStorage;
    @Autowired FaceTemporaryImageCleanup cleanup;
    @Autowired FaceTaskRepository faceTasks;
    @Autowired FaceInfoRepository faceInfo;
    @Autowired EventPhotoRepository photos;
    @Autowired DeviceEventReceiptRepository events;
    @Autowired GeneratedReportReceiptRepository submissions;
    @Autowired DailyReportRepository reports;
    @Autowired IntegratedLogRepository logs;
    @Autowired UserController userController;
    @Autowired DeviceController deviceController;
    @Autowired CameraCaptureController captureController;
    @Autowired ImageUploadController imageController;
    @Autowired IntegratedLogController logController;
    @Autowired TrustedWriteController writeController;
    @Autowired TaskController taskController;
    MockMvc mvc;
    User owner, other;
    String app, otherApp;

    @BeforeEach void setup() {
        tasks.deleteAll(); registered.deleteAll(); sessionRows.deleteAll(); buckets.deleteAll();
        faceTasks.deleteAll(); faceInfo.deleteAll(); photos.deleteAll(); events.deleteAll(); submissions.deleteAll();
        reports.deleteAll(); logs.deleteAll();
        owner = user(); other = user();
        app = bearer(sessions.issue(owner.getUserNo()).sessionToken());
        otherApp = bearer(sessions.issue(other.getUserNo()).sessionToken());
        mvc = MockMvcBuilders.standaloneSetup(userController, deviceController, captureController,
                imageController, logController, writeController, taskController).build();
    }

    @Test void loginReturnsCompatibleUserAndIndependentExpiringRevocableSessionsWithoutPassword() throws Exception {
        mvc.perform(post("/api/users/login").contentType("application/json")
                .content("{\"id\":\"" + owner.getUserId() + "\",\"pw\":\"test-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userNo").value(owner.getUserNo()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.sessionToken").isString()).andExpect(jsonPath("$.sessionExpiresAt").isString());
        String second = bearer(sessions.issue(owner.getUserNo()).sessionToken());
        assertThat(sessions.requireUser(app)).isEqualTo(owner.getUserNo());
        mvc.perform(post("/api/users/logout").header("Authorization", app)).andExpect(status().isNoContent());
        expect(HttpStatus.UNAUTHORIZED, () -> sessions.requireUser(app));
        assertThat(sessions.requireUser(second)).isEqualTo(owner.getUserNo());
        AppSession row = sessionRows.findById(TokenSecrets.hash(TokenSecrets.bearer(second))).orElseThrow();
        assertThat(row.getTokenHash()).doesNotContain(TokenSecrets.bearer(second));
        row.setExpiresAt(Instant.now().minusSeconds(1)); sessionRows.save(row);
        expect(HttpStatus.UNAUTHORIZED, () -> sessions.requireUser(second));
    }

    @Test void reenrollKeepsTokenCodeRoleAndPairedOwnerAndDeviceCannotClaimAsAnApp() throws Exception {
        String token = token();
        var first = devices.enroll(token, new DeviceRegistrationService.EnrollRequest(DeviceRole.CAMERA, "Camera"), "enroll");
        var retry = devices.enroll(token, new DeviceRegistrationService.EnrollRequest(DeviceRole.CAMERA, "Changed"), "enroll");
        assertThat(retry.deviceId()).isEqualTo(first.deviceId());
        assertThat(retry.pairingCode()).isEqualTo(first.pairingCode()).matches("[0-9]{8}");
        expect(HttpStatus.CONFLICT, () -> devices.enroll(token,
                new DeviceRegistrationService.EnrollRequest(DeviceRole.SENSOR, "Sensor"), "enroll"));
        expect(HttpStatus.FORBIDDEN, () -> devices.require(token, DeviceRole.CAMERA, null));
        mvc.perform(post("/api/devices/claim").header("Authorization", token).contentType("application/json")
                .content("{\"code\":\"" + first.pairingCode() + "\"}")).andExpect(status().isUnauthorized());
        assertThat(devices.claim(app, first.pairingCode(), "pair").userNo()).isEqualTo(owner.getUserNo());
        var pairedRetry = devices.enroll(token, new DeviceRegistrationService.EnrollRequest(DeviceRole.CAMERA, "Camera"), "enroll");
        assertThat(pairedRetry.pairingCode()).isNull();
        assertThat(pairedRetry.userNo()).isEqualTo(owner.getUserNo());
        assertThat(registered.findById(first.deviceId()).orElseThrow().getTokenHash()).isEqualTo(TokenSecrets.hash(TokenSecrets.bearer(token)));
        expect(HttpStatus.FORBIDDEN, () -> devices.require(token, DeviceRole.SENSOR, null));
        expect(HttpStatus.FORBIDDEN, () -> devices.require(token, DeviceRole.CAMERA, other.getUserNo()));
    }

    @Test void expiredCodeIsRejectedThenRotatedWithoutChangingDeviceIdentity() {
        String token = token(); var first = enroll(token, DeviceRole.REPORT);
        PairedDevice row = registered.findById(first.deviceId()).orElseThrow();
        row.setPairingExpiresAt(Instant.now().minusSeconds(1)); registered.save(row);
        expect(HttpStatus.GONE, () -> devices.claim(app, first.pairingCode(), "expired"));
        var next = enroll(token, DeviceRole.REPORT);
        assertThat(next.deviceId()).isEqualTo(first.deviceId());
        assertThat(next.pairingCode()).isNotEqualTo(first.pairingCode());
        devices.claim(app, next.pairingCode(), "expired");
        assertThat(devices.status(token).paired()).isTrue();
    }

    @Test void invalidCodeAttemptsCommitAndRateLimitCannotBeRolledBack() throws Exception {
        for (int i = 0; i < 10; i++) {
            expect(HttpStatus.NOT_FOUND, () -> devices.claim(app, "00000000", "bruteforce"));
        }
        mvc.perform(post("/api/devices/claim").header("Authorization", app).contentType("application/json")
                .content("{\"code\":\"00000000\"}")).andExpect(status().isTooManyRequests());
        assertThat(buckets.findById(TokenSecrets.hash("pair-account:" + owner.getUserNo())).orElseThrow().getAttempts()).isEqualTo(10);
    }

    @Test void concurrentClaimsCannotBindOneDeviceToTwoAccounts() throws Exception {
        String token = token(); var pending = enroll(token, DeviceRole.CAMERA);
        List<Integer> codes = race(
                () -> statusOf(() -> devices.claim(app, pending.pairingCode(), "one")),
                () -> statusOf(() -> devices.claim(otherApp, pending.pairingCode(), "two")));
        assertThat(codes.stream().filter(code -> code == 200).count()).isEqualTo(1);
        assertThat(codes).allMatch(code -> code == 200 || code == 404 || code == 409);
        assertThat(registered.count()).isEqualTo(1);
        assertThat(devices.status(token).userNo()).isIn(owner.getUserNo(), other.getUserNo());
    }

    @Test void concurrentSameRoleClaimsKeepOneDeviceAndUnlinkDoesNotTransferOwner() throws Exception {
        String a = token(), b = token(); var one = enroll(a, DeviceRole.CAMERA); var two = enroll(b, DeviceRole.CAMERA);
        var codes = race(() -> statusOf(() -> devices.claim(app, one.pairingCode(), "one")),
                () -> statusOf(() -> devices.claim(app, two.pairingCode(), "two")));
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        var linked = devices.list(app).getFirst();
        String linkedToken = linked.deviceId().equals(one.deviceId()) ? a : b;
        expect(HttpStatus.NOT_FOUND, () -> devices.unlink(otherApp, linked.deviceId()));
        devices.unlink(app, linked.deviceId());
        assertThat(devices.status(linkedToken).paired()).isFalse();
        expect(HttpStatus.FORBIDDEN, () -> devices.require(linkedToken, DeviceRole.CAMERA, null));
        var fresh = enroll(linkedToken, DeviceRole.CAMERA);
        assertThat(fresh.deviceId()).isEqualTo(linked.deviceId());
        devices.claim(otherApp, fresh.pairingCode(), "other");
        assertThat(devices.status(linkedToken).userNo()).isEqualTo(other.getUserNo());
    }

    @Test void pairedReportUsesOnlyBoundAccountAndWrongRolesCannotWriteOrReadReports() throws Exception {
        String report = pair(DeviceRole.REPORT, app), sensor = pair(DeviceRole.SENSOR, app);
        var event = writes.saveEvent(sensor, event("paired-owner", null, OffsetDateTime.now()));
        assertThat(event.userNo()).isEqualTo(owner.getUserNo());
        assertThat(event.cameraCaptureQueued()).isFalse();
        assertThat(event.captureStatus()).isEqualTo("NOT_REQUESTED");
        var summary = writes.saveReport(report, new TrustedWriteService.ReportRequest("report", null, event.eventDate(), "summary"));
        assertThat(summary.userNo()).isEqualTo(owner.getUserNo());
        expect(HttpStatus.FORBIDDEN, () -> writes.saveEvent(report, event("wrong-role", null, OffsetDateTime.now())));
        expect(HttpStatus.FORBIDDEN, () -> writes.saveEvent(sensor, event("wrong-owner", other.getUserNo(), OffsetDateTime.now())));
        mvc.perform(get("/api/logs").param("date", event.eventDate().toString()).header("Authorization", report))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].userNo").value(owner.getUserNo()));
        mvc.perform(get("/api/logs").param("date", event.eventDate().toString()).header("Authorization", sensor))
                .andExpect(status().isForbidden());
    }

    @Test void duplicateEventsAndConcurrentCapturePollsProduceExactlyOneLeaseAndPhotoAckIsDurable() throws Exception {
        String cameraToken = pair(DeviceRole.CAMERA, app), sensorToken = pair(DeviceRole.SENSOR, app);
        var camera = devices.require(cameraToken, DeviceRole.CAMERA, null);
        var request = event("capture-once", null, OffsetDateTime.now());
        var ack = writes.saveEvent(sensorToken, request);
        assertThat(ack.cameraCaptureQueued()).isTrue(); assertThat(ack.captureStatus()).isEqualTo("QUEUED");
        assertThat(writes.saveEvent(sensorToken, request).captureTaskId()).isEqualTo(ack.captureTaskId());
        var claims = race(() -> captures.claim(camera), () -> captures.claim(camera));
        assertThat(claims.stream().filter(Objects::nonNull).count()).isEqualTo(1);
        var lease = claims.stream().filter(Objects::nonNull).findFirst().orElseThrow();
        expect(HttpStatus.CONFLICT, () -> captures.result(camera, lease.captureTaskId(), lease.leaseToken(), "success", ""));
        expect(HttpStatus.BAD_REQUEST, () -> cameraPhotos.save(camera,
                new MockMultipartFile("file", "bad.jpg", "image/jpeg", new byte[]{1}), request.eventId(), lease.captureTaskId(), lease.leaseToken()));
        assertThat(tasks.findById(lease.captureTaskId()).orElseThrow().getStatus()).isEqualTo(CameraCaptureTask.Status.RUNNING);
        var photo = cameraPhotos.save(camera, jpeg(), request.eventId(), lease.captureTaskId(), lease.leaseToken());
        CameraCaptureTask task = tasks.findById(lease.captureTaskId()).orElseThrow();
        task.setExpiresAt(Instant.now().minusSeconds(1)); tasks.save(task);
        var retry = cameraPhotos.save(camera, jpeg(), request.eventId(), lease.captureTaskId(), lease.leaseToken());
        assertThat(retry.duplicate()).isTrue(); assertThat(retry.url()).isEqualTo(photo.url());
        captures.result(camera, lease.captureTaskId(), lease.leaseToken(), "success", "");
        assertThat(photos.count()).isEqualTo(1); assertThat(logs.count()).isEqualTo(1); assertThat(tasks.count()).isEqualTo(1);
    }

    @Test void staleLeaseAndOtherCameraCannotUploadAndCameraTokenDeterminesMultipartOwner() throws Exception {
        String cameraToken = pair(DeviceRole.CAMERA, app), sensor = pair(DeviceRole.SENSOR, app);
        String strangerToken = pair(DeviceRole.CAMERA, otherApp);
        var camera = devices.require(cameraToken, DeviceRole.CAMERA, null);
        var stranger = devices.require(strangerToken, DeviceRole.CAMERA, null);
        writes.saveEvent(sensor, event("lease-retry", null, OffsetDateTime.now()));
        var old = captures.claim(camera);
        CameraCaptureTask task = tasks.findById(old.captureTaskId()).orElseThrow();
        task.setLeaseUntil(Instant.now().minusSeconds(1)); tasks.save(task);
        var current = captures.claim(camera);
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        expect(HttpStatus.CONFLICT, () -> cameraPhotos.save(camera, jpeg(), "lease-retry", old.captureTaskId(), old.leaseToken()));
        expect(HttpStatus.FORBIDDEN, () -> cameraPhotos.save(stranger, jpeg(), "lease-retry", current.captureTaskId(), current.leaseToken()));
        mvc.perform(multipart("/api/upload").file(jpeg()).param("log_id", "lease-retry")
                .param("captureTaskId", current.captureTaskId()).param("leaseToken", current.leaseToken())
                .param("userNo", other.getUserNo().toString()).header("Authorization", cameraToken)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/upload").file(jpeg()).param("log_id", "lease-retry")
                .param("captureTaskId", current.captureTaskId()).param("leaseToken", current.leaseToken())
                .header("Authorization", cameraToken)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(multipart("/api/upload").file(jpeg()).param("log_id", "forged-legacy")
                .param("date", LocalDate.now().toString()).param("userNo", owner.getUserNo().toString())).andExpect(status().isUnauthorized());
        assertThat(photos.findAll().getFirst().getUserNo()).isEqualTo(owner.getUserNo());
    }

    @Test void captureDeadlineAndAttemptLimitExpireWorkWithoutLatePhotos() {
        String cameraToken = pair(DeviceRole.CAMERA, app), sensor = pair(DeviceRole.SENSOR, app);
        var camera = devices.require(cameraToken, DeviceRole.CAMERA, null);
        var expired = writes.saveEvent(sensor, event("old-event", null, OffsetDateTime.now().minusMinutes(3)));
        assertThat(expired.captureStatus()).isEqualTo("EXPIRED"); assertThat(tasks.count()).isZero();
        writes.saveEvent(sensor, event("expires", null, OffsetDateTime.now()));
        var lease = captures.claim(camera); var task = tasks.findById(lease.captureTaskId()).orElseThrow();
        task.setExpiresAt(Instant.now().minusSeconds(1)); tasks.save(task);
        assertThat(captures.claim(camera)).isNull();
        assertThat(tasks.findById(lease.captureTaskId()).orElseThrow().getStatus()).isEqualTo(CameraCaptureTask.Status.EXPIRED);
        writes.saveEvent(sensor, event("exhausted", null, OffsetDateTime.now()));
        lease = captures.claim(camera); task = tasks.findById(lease.captureTaskId()).orElseThrow();
        task.setAttempts(3); task.setLeaseUntil(Instant.now().minusSeconds(1)); tasks.save(task);
        assertThat(captures.claim(camera)).isNull();
        assertThat(tasks.findById(lease.captureTaskId()).orElseThrow().getStatus()).isEqualTo(CameraCaptureTask.Status.FAILED);
    }

    @Test void reassignedCameraCannotPollPreviousOwnersCapture() {
        String cameraToken = pair(DeviceRole.CAMERA, app), sensor = pair(DeviceRole.SENSOR, app);
        var oldIdentity = devices.require(cameraToken, DeviceRole.CAMERA, null);
        writes.saveEvent(sensor, event("old-owner-event", null, OffsetDateTime.now()));
        devices.unlink(app, oldIdentity.deviceId());
        var reenroll = enroll(cameraToken, DeviceRole.CAMERA);
        devices.claim(otherApp, reenroll.pairingCode(), "other");
        assertThat(captures.claim(devices.require(cameraToken, DeviceRole.CAMERA, null))).isNull();
    }

    @Test void faceQueueIsScopedToPairedCameraAndLegacyWorkerCannotTakeItsRequests() throws Exception {
        String cameraToken = pair(DeviceRole.CAMERA, app);
        var camera = devices.require(cameraToken, DeviceRole.CAMERA, null);
        var first = faces.registerFace(owner.getUserId(), List.of(jpeg(), jpeg(), jpeg()));
        var second = faces.registerFace(other.getUserId(), List.of(jpeg(), jpeg(), jpeg()));
        var legacy = faces.getNextTask("door-camera-a", null);
        assertThat(legacy.get("task_id")).isEqualTo(second.taskId());
        var task = faces.getNextTask(camera);
        assertThat(task.get("task_id")).isEqualTo(first.taskId());
        assertThat(task.get("user_no")).isEqualTo(owner.getUserNo());
        String otherCamera = pair(DeviceRole.CAMERA, otherApp);
        expect(HttpStatus.FORBIDDEN, () -> faces.recordResult(first.taskId(), "TRAIN",
                devices.require(otherCamera, DeviceRole.CAMERA, null), (String) task.get("lease_token"), "success", ""));
        faces.recordResult(first.taskId(), "TRAIN", camera, (String) task.get("lease_token"), "success", "done");
        assertThat(faces.getStatus(first.taskId(), owner.getUserId()).status()).isEqualTo(FaceTaskStatus.SUCCEEDED);
        assertThat(faceStorage.hasImages(first.taskId())).isFalse();
        faces.recordResult(first.taskId(), "TRAIN", camera, (String) task.get("lease_token"), "success", "ACK retry");
    }

    @Test void pendingFaceImagesExpireAfter24HoursAndCleanupCanSafelyRepeat() throws Exception {
        var response=faces.registerFace(owner.getUserId(),List.of(jpeg(),jpeg(),jpeg()));
        assertThat(faceStorage.hasImages(response.taskId())).isTrue();
        var task=faceTasks.findById(response.taskId()).orElseThrow();
        task.setCreatedAt(Instant.now().minus(Duration.ofHours(25))); faceTasks.saveAndFlush(task);
        cleanup.cleanup(); cleanup.cleanup();
        var expired=faceTasks.findById(response.taskId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(FaceTaskStatus.FAILED);
        assertThat(expired.isTemporaryImagesDeleted()).isTrue();
        assertThat(faceStorage.hasImages(response.taskId())).isFalse();
    }

    private User user() {
        User user = new User(); user.setUserId("test-" + UUID.randomUUID()); user.setPasswordHash("test-password");
        user.setUsername("Test"); return users.saveAndFlush(user);
    }
    private String pair(DeviceRole role, String appToken) {
        String token = token(); var registration = enroll(token, role);
        devices.claim(appToken, registration.pairingCode(), "pair-" + role);
        return token;
    }
    private DeviceRegistrationService.DeviceView enroll(String token, DeviceRole role) {
        return devices.enroll(token, new DeviceRegistrationService.EnrollRequest(role, role.name()), "enroll");
    }
    private static String token() { return bearer(TokenSecrets.generate()); }
    private static String bearer(String token) { return "Bearer " + token; }
    private static TrustedWriteService.EventRequest event(String id, Integer userNo, OffsetDateTime at) {
        return new TrustedWriteService.EventRequest(id, userNo, at, IntegratedLog.LogType.SECURITY,
                "door", 2f, 0f, IntegratedLog.Severity.high, "impact");
    }
    private static MockMultipartFile jpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", out.toByteArray());
    }
    private static void expect(HttpStatus expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(expected));
    }
    private static int statusOf(Runnable action) {
        try { action.run(); return 200; } catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
    }
    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return first.call(); });
            var b = pool.submit(() -> { gate.await(); return second.call(); });
            gate.countDown();
            return Arrays.asList(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
}
