package com.example.smart_door_security_server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "DEVICE_API_TOKEN=device-test-token-with-at-least-32-characters", "DEVICE_USER_NO=900001",
        "REPORT_API_TOKEN=report-test-token-with-at-least-32-characters", "REPORT_USER_NO=900001"
})
class TrustedWriteServiceTests {
    static final int OWNER = 900001;
    static final String DEVICE = "Bearer device-test-token-with-at-least-32-characters";
    static final String REPORT = "Bearer report-test-token-with-at-least-32-characters";
    static final LocalDate DATE = LocalDate.of(2026, 1, 2);
    @TempDir static Path storage;
    @DynamicPropertySource static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage-dir", () -> storage.toString());
    }

    @Autowired TrustedWriteService service;
    @Autowired TrustedWriteController writesController;
    @Autowired IntegratedLogController logsController;
    @Autowired UserRepository users;
    @Autowired IntegratedLogRepository logs;
    @Autowired DailyReportRepository reports;
    @Autowired DeviceEventReceiptRepository eventReceipts;
    @Autowired GeneratedReportReceiptRepository reportReceipts;
    @Autowired EventPhotoRepository eventPhotos;
    @Autowired PhotoUploadService photos;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    MockMvc mvc;

    @BeforeEach void prepare() {
        eventReceipts.deleteAll();
        reportReceipts.deleteAll();
        eventPhotos.deleteAll();
        reports.deleteAll();
        logs.deleteAll();
        if (!users.existsById(OWNER)) jdbc.update("INSERT INTO users (user_no,user_id) VALUES (?,?)", OWNER, "trusted-writer-owner");
        mvc = MockMvcBuilders.standaloneSetup(writesController, logsController).build();
    }

    @Test void delayedEventKeepsOccurrenceDayAndReplaysExactlyOnce() {
        var request = event("event-retry");
        var first = service.saveEvent(DEVICE, request);
        var replay = service.saveEvent(DEVICE, request);
        assertThat(first.duplicate()).isFalse();
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.logId()).isEqualTo(first.logId());
        assertThat(first.eventDate()).isEqualTo(DATE);
        assertThat(logs.findById(first.logId()).orElseThrow().getCreatedAt()).isEqualTo(DATE.atTime(23, 59, 50));
        assertThat(logs.count()).isEqualTo(1);
        DailyReport report = report();
        assertThat(report.getTotalEvents()).isEqualTo(1);
        assertThat(report.getHighRiskEvents()).isEqualTo(1);
        var changed = new TrustedWriteService.EventRequest(request.eventId(), OWNER, request.occurredAt(),
                request.logType(), request.subType(), 99f, request.val2(), request.severity(), request.description());
        assertStatus(HttpStatus.CONFLICT, () -> service.saveEvent(DEVICE, changed));
        assertThat(logs.count()).isEqualTo(1);
    }

    @Test void competingCopiesOfTheSameEventProduceOneLogAndReceipt() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return service.saveEvent(DEVICE, event("parallel-event")); });
            var second = pool.submit(() -> { start.await(); return service.saveEvent(DEVICE, event("parallel-event")); });
            start.countDown();
            var one = first.get(15, TimeUnit.SECONDS);
            var two = second.get(15, TimeUnit.SECONDS);
            assertThat(one.logId()).isEqualTo(two.logId());
            assertThat(one.duplicate()).isNotEqualTo(two.duplicate());
        }
        assertThat(logs.count()).isEqualTo(1);
        assertThat(eventReceipts.count()).isEqualTo(1);
        assertThat(report().getTotalEvents()).isEqualTo(1);
    }

    @Test void delayedPhotoUsesRegisteredEventDateAndReportTextNeverOverwritesPhotosOrCounts() throws Exception {
        service.saveEvent(DEVICE, event("event-with-photo"));
        var photo = photos.save(jpeg(), OWNER, "event-with-photo", DATE.plusDays(1));
        assertThat(report().getPhotoUrl()).isEqualTo(photo.url());
        assertThat(reports.findByUserAndReportDate(users.findById(OWNER).orElseThrow(), DATE.plusDays(1))).isEmpty();
        assertThat(eventPhotos.findByUserNoAndSourceEventId(OWNER, "event-with-photo").orElseThrow().getEventDate()).isEqualTo(DATE);
        var result = service.saveReport(REPORT, generated("report-first", "AI summary"));
        assertThat(result.totalEvents()).isEqualTo(1);
        assertThat(result.highRiskEvents()).isEqualTo(1);
        assertThat(report().getPhotoUrl()).isEqualTo(photo.url());
        assertThat(report().getReportText()).isEqualTo("AI summary");
        assertThat(photos.save(jpeg(), OWNER, "event-with-photo", DATE.plusDays(2)).duplicate()).isTrue();
        assertThat(report().getPhotoUrl()).isEqualTo(photo.url());
    }

    @Test void olderReportAckReplayDoesNotOverwriteANewerSummaryAndChangedPayloadConflicts() {
        service.saveEvent(DEVICE, event("report-event"));
        var original = generated("report-original", "first summary");
        service.saveReport(REPORT, original);
        service.saveReport(REPORT, generated("report-newer", "newer summary"));
        assertThat(service.saveReport(REPORT, original).duplicate()).isTrue();
        assertThat(report().getReportText()).isEqualTo("newer summary");
        assertStatus(HttpStatus.CONFLICT, () -> service.saveReport(REPORT, generated("report-original", "changed content")));
        assertStatus(HttpStatus.CONFLICT, () -> service.saveReport(REPORT,
                new TrustedWriteService.ReportRequest("report-original", OWNER, DATE.plusDays(1), "first summary")));
        assertThat(reportReceipts.count()).isEqualTo(2);
    }

    @Test void simultaneousPhotoAndReportPreserveBothResults() throws Exception {
        service.saveEvent(DEVICE, event("parallel-photo"));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var image = pool.submit(() -> { start.await(); return photos.save(jpeg(), OWNER, "parallel-photo", DATE); });
            var summary = pool.submit(() -> { start.await(); return service.saveReport(REPORT, generated("parallel-report", "kept text")); });
            start.countDown();
            var storedImage = image.get(15, TimeUnit.SECONDS);
            summary.get(15, TimeUnit.SECONDS);
            assertThat(report().getPhotoUrl()).isEqualTo(storedImage.url());
        }
        assertThat(report().getReportText()).isEqualTo("kept text");
        assertThat(report().getTotalEvents()).isEqualTo(1);
        assertThat(reports.findByUser_UserNoOrderByReportDateDesc(OWNER)).hasSize(1);
    }

    @Test void requestsAreBoundToSeparateTokensAndTheConfiguredOwner() {
        assertStatus(HttpStatus.UNAUTHORIZED, () -> service.saveEvent(null, event("no-auth")));
        assertStatus(HttpStatus.UNAUTHORIZED, () -> service.saveEvent(REPORT, event("wrong-role")));
        assertStatus(HttpStatus.UNAUTHORIZED, () -> service.saveReport(DEVICE, generated("wrong-role", "text")));
        var normal = event("other-owner");
        assertStatus(HttpStatus.FORBIDDEN, () -> service.saveEvent(DEVICE,
                new TrustedWriteService.EventRequest(normal.eventId(), OWNER + 1, normal.occurredAt(), normal.logType(),
                        normal.subType(), normal.val1(), normal.val2(), normal.severity(), normal.description())));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.saveReport(REPORT,
                new TrustedWriteService.ReportRequest("other-owner", OWNER + 1, DATE, "text")));
        assertThat(logs.count()).isZero();
        assertThat(reports.count()).isZero();
    }

    @Test void dateFilteredReadRequiresReportTokenWhileExistingCurrentDayReadRemainsCompatible() throws Exception {
        service.saveEvent(DEVICE, event("read-event"));
        mvc.perform(get("/api/logs").param("userNo", Integer.toString(OWNER)).param("date", DATE.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/logs").param("userNo", Integer.toString(OWNER + 1)).param("date", DATE.toString()).header("Authorization", REPORT))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/logs").param("userNo", Integer.toString(OWNER)).param("date", DATE.toString()).header("Authorization", REPORT))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].logType").value("SECURITY"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-01-02T23:59:50"));
        mvc.perform(get("/api/logs").param("userNo", Integer.toString(OWNER))).andExpect(status().isOk());
    }

    @Test void httpContractsAcceptOffsetTimesAndRejectAmbiguousTimes() throws Exception {
        String payload = """
                {"eventId":"http-event","userNo":900001,"occurredAt":"2026-01-02T23:59:50+09:00",
                 "logType":"SECURITY","subType":"door","val1":2,"val2":0,"severity":"high","description":"impact"}
                """;
        mvc.perform(post("/api/device/events").header("Authorization", DEVICE).contentType("application/json").content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.eventDate").value(DATE.toString())).andExpect(jsonPath("$.duplicate").value(false));
        mvc.perform(post("/api/device/events").header("Authorization", DEVICE).contentType("application/json")
                        .content(payload.replace("+09:00", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/reports/generated").header("Authorization", REPORT).contentType("application/json")
                        .content("{\"requestId\":\"http-report\",\"userNo\":900001,\"reportDate\":\"2026-01-02\",\"reportText\":\"summary\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.totalEvents").value(1));
    }

    @Test void transactionRollbackLeavesNoPartiallyRegisteredEventOrSummary() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.saveEvent(DEVICE, event("rolled-back-event"));
            service.saveReport(REPORT, generated("rolled-back-report", "discarded"));
            status.setRollbackOnly();
        });
        assertThat(logs.count()).isZero();
        assertThat(eventReceipts.count()).isZero();
        assertThat(reportReceipts.count()).isZero();
        assertThat(reports.count()).isZero();
    }

    private DailyReport report() {
        return reports.findByUserAndReportDate(users.findById(OWNER).orElseThrow(), DATE).orElseThrow();
    }
    private static TrustedWriteService.EventRequest event(String id) {
        return new TrustedWriteService.EventRequest(id, OWNER, OffsetDateTime.parse("2026-01-02T23:59:50+09:00"),
                IntegratedLog.LogType.SECURITY, "door", 2f, 0f, IntegratedLog.Severity.high, "impact detected");
    }
    private static TrustedWriteService.ReportRequest generated(String id, String text) {
        return new TrustedWriteService.ReportRequest(id, OWNER, DATE, text);
    }
    private static MockMultipartFile jpeg() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "jpg", bytes);
        return new MockMultipartFile("file", "event.jpg", "image/jpeg", bytes.toByteArray());
    }
    private static void assertStatus(HttpStatus status, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }
}
