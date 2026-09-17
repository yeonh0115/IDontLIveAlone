package com.example.smart_door_security_server;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="EVENT_PHOTOS_ENABLED=false")
class EventPhotosDisabledTests {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("app.storage-dir", () -> storage.toString());
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:photos-disabled;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1");
    }
    @Autowired UserRepository users;
    @Autowired AppSessionService sessions;
    @Autowired DeviceRegistrationService devices;
    @Autowired TrustedWriteService writes;
    @Autowired CameraCaptureService captures;
    @Autowired CameraCaptureTaskRepository tasks;
    @Autowired DailyReportRepository reports;
    @Autowired EventPhotoRepository photos;
    @Autowired ImageUploadController uploadController;
    @Autowired DailyReportController reportController;
    @Autowired CameraCaptureController captureController;
    @Autowired EventPhotoDisabledHandler disabledHandler;
    @Autowired PhotoUploadService photoService;
    MockMvc mvc;
    User owner;
    String app, sensor, camera;
    @BeforeEach void setup() {
        User user = new User(); user.setUserId("disabled-" + UUID.randomUUID()); owner=users.saveAndFlush(user);
        app="Bearer " + sessions.issue(owner.getUserNo()).sessionToken();
        sensor=pair(DeviceRole.SENSOR); camera=pair(DeviceRole.CAMERA);
        mvc=MockMvcBuilders.standaloneSetup(uploadController,reportController,captureController)
                .setControllerAdvice(disabledHandler).build();
    }

    @Test void eventLogIsSavedButCaptureNeverQueuedAndEveryNewPhotoWriteIsDisabled() throws Exception {
        long before=tasks.count();
        var request=new TrustedWriteService.EventRequest("off-event",null,OffsetDateTime.now(),
                IntegratedLog.LogType.SECURITY,"door",2f,0f,IntegratedLog.Severity.high,"impact");
        var ack=writes.saveEvent(sensor,request);
        assertThat(ack.cameraCaptureQueued()).isFalse(); assertThat(ack.captureStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(ack.captureTaskId()).isNull(); assertThat(ack.logId()).isPositive();
        assertThat(tasks.count()).isEqualTo(before);
        mvc.perform(get("/api/camera/captures").header("Authorization",camera)).andExpect(status().isNoContent());
        mvc.perform(multipart("/api/upload").file(new MockMultipartFile("file","event.jpg","image/jpeg",new byte[]{1}))
                .param("log_id","off-event").param("date",ack.eventDate().toString())
                .param("userNo",owner.getUserNo().toString()).header("Authorization",camera))
                .andExpect(status().isGone()).andExpect(jsonPath("$.status").value("disabled"))
                .andExpect(jsonPath("$.success").value(false));
        assertThatThrownBy(() -> photoService.save(null,owner.getUserNo(),"direct",ack.eventDate()))
                .isInstanceOf(EventPhotoSettings.Disabled.class);
        try(var files=Files.list(storage.resolve("uploads"))) { assertThat(files.count()).isZero(); }
        assertThat(photos.findByUserNoAndSourceEventId(owner.getUserNo(),"off-event")).isEmpty();
    }

    @Test void reportReadHidesOldPhotoUrlsWithoutDeletingExistingReportData() throws Exception {
        DailyReport report=new DailyReport(); report.setUser(owner); report.setReportDate(LocalDate.now());
        report.setReportText("Keep this summary"); report.setPhotoUrl("https://example.test/old-photo.jpg");
        report.setTotalEvents(2); report.setHighRiskEvents(1); reports.saveAndFlush(report);
        mvc.perform(get("/api/reports/"+owner.getUserNo())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].photoUrl").value(""))
                .andExpect(jsonPath("$[0].reportText").value("Keep this summary"))
                .andExpect(jsonPath("$[0].totalEvents").value(2));
        assertThat(reports.findById(report.getId()).orElseThrow().getPhotoUrl()).isEqualTo("https://example.test/old-photo.jpg");
    }
    private String pair(DeviceRole role) {
        String token="Bearer "+TokenSecrets.generate();
        var pending=devices.enroll(token,new DeviceRegistrationService.EnrollRequest(role,role.name()),UUID.randomUUID().toString());
        devices.claim(app,pending.pairingCode(),UUID.randomUUID().toString()); return token;
    }
}
