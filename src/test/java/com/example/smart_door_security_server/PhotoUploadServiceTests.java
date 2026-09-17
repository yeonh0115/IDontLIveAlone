package com.example.smart_door_security_server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhotoUploadServiceTests {
    @TempDir static Path storage;
    @DynamicPropertySource static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage-dir", () -> storage.toString());
    }
    @Autowired PhotoUploadService service;
    @Autowired UserRepository users;
    @Autowired DailyReportRepository reports;
    @Autowired EventPhotoRepository photos;
    @Autowired IntegratedLogRepository logs;
    @Autowired DailyReportController controller;

    private User newUser() {
        User user = new User();
        user.setUserId(UUID.randomUUID().toString());
        user.setUsername("test");
        return users.saveAndFlush(user);
    }
    private MockMultipartFile jpeg() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "jpg", bytes);
        return new MockMultipartFile("file", "../../untrusted.jpg", "image/jpeg", bytes.toByteArray());
    }

    @Test void usesRequestedOwnerAndKeepsEveryPhotoBeyondOldColumnLimit() throws Exception {
        User unrelated = newUser();
        User owner = newUser();
        LocalDate date = LocalDate.of(2026, 9, 16);
        for (int i = 0; i < 8; i++) service.save(jpeg(), owner.getUserNo(), "event-" + i, date);
        DailyReport report = reports.findByUserAndReportDate(owner, date).orElseThrow();
        assertEquals(owner.getUserNo(), report.getUser().getUserNo());
        assertEquals(8, report.getPhotoUrl().split(",").length);
        assertTrue(report.getPhotoUrl().length() > 500);
        assertEquals(0, report.getHighRiskEvents()); // An image alone is not proof of danger.
        assertTrue(reports.findByUserAndReportDate(unrelated, date).isEmpty());
        assertEquals(1, controller.getUserReports(owner.getUserNo()).size());
        assertEquals(owner.getUserNo(), reports.findById(report.getId()).orElseThrow().getUser().getUserNo());
    }

    @Test void retryReturnsSamePhotoEvenWhenAcknowledgementRetryCrossesMidnight() throws Exception {
        User owner = newUser();
        LocalDate date = LocalDate.of(2026, 9, 16);
        var first = service.save(jpeg(), owner.getUserNo(), "stable-event-id", date);
        var retry = service.save(jpeg(), owner.getUserNo(), "stable-event-id", date);
        assertEquals(first.url(), retry.url());
        assertTrue(retry.duplicate());
        assertFalse(first.duplicate());
        assertEquals(1, reports.findByUserAndReportDate(owner, date).orElseThrow().getPhotoUrl().split(",").length);
        var nextDayRetry = service.save(jpeg(), owner.getUserNo(), "stable-event-id", date.plusDays(1));
        assertEquals(first.url(), nextDayRetry.url());
        assertTrue(nextDayRetry.duplicate());
        assertTrue(reports.findByUserAndReportDate(owner, date.plusDays(1)).isEmpty());
    }

    @Test void simultaneousFirstPhotosDoNotOverwriteOrDuplicateDailyReport() throws Exception {
        User owner = newUser();
        LocalDate date = LocalDate.of(2026, 9, 16);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> service.save(jpeg(), owner.getUserNo(), "parallel-a", date));
            var two = executor.submit(() -> service.save(jpeg(), owner.getUserNo(), "parallel-b", date));
            one.get(15, TimeUnit.SECONDS);
            two.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, reports.findByUser_UserNoOrderByReportDateDesc(owner.getUserNo()).size());
        assertEquals(2, reports.findByUserAndReportDate(owner, date).orElseThrow().getPhotoUrl().split(",").length);
    }

    @Test void laterPhotosRefreshCountsFromActualOwnerLogs() throws Exception {
        User owner = newUser();
        LocalDate date = LocalDate.now();
        service.save(jpeg(), owner.getUserNo(), "before-log", date);
        IntegratedLog event = new IntegratedLog();
        event.setUserNo(owner.getUserNo());
        event.setLogType(IntegratedLog.LogType.SECURITY);
        event.setSeverity(IntegratedLog.Severity.high);
        logs.saveAndFlush(event);
        service.save(jpeg(), owner.getUserNo(), "after-log", date);
        DailyReport report = reports.findByUserAndReportDate(owner, date).orElseThrow();
        assertEquals(1, report.getTotalEvents());
        assertEquals(1, report.getHighRiskEvents());
        assertEquals(2, report.getPhotoUrl().split(",").length);
    }

    @Test void ownerAndRealImageAreRequired() throws Exception {
        User owner = newUser();
        var fake = new MockMultipartFile("file", "fake.jpg", "image/jpeg", "not an image".getBytes());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.save(fake, owner.getUserNo(), "invalid-image", LocalDate.now())).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.save(jpeg(), null, "missing-owner", LocalDate.now())).getStatusCode().value());
        assertTrue(reports.findByUser_UserNoOrderByReportDateDesc(owner.getUserNo()).isEmpty());
    }
}
