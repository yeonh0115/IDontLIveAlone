package com.example.smart_door_security_server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FaceTaskServiceTests {
    private static final String DEVICE = "door-camera-a";
    @Autowired TaskQueueService service;
    @Autowired FaceTaskRepository tasks;
    @Autowired FaceInfoRepository faces;
    @Autowired UserRepository users;
    @Autowired FaceImageStorage storage;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearTasks() {
        tasks.deleteAll();
        faces.deleteAll();
    }

    @Test
    void registrationPersistsAcrossServiceRecreationWithOwnerNumberAndSafeImagePaths() throws Exception {
        User owner = owner("../face-" + UUID.randomUUID().toString().substring(0, 8));
        FaceTaskResponse response = service.registerFace(owner.getUserId(), images());
        assertThat(response.status()).isEqualTo(FaceTaskStatus.QUEUED);
        FaceTask stored = tasks.findById(response.taskId()).orElseThrow();
        assertThat(stored.getUserNo()).isEqualTo(owner.getUserNo());
        assertThat(stored.imageUrls()).allMatch(url -> url.startsWith("/pictures/" + response.taskId() + "/"));
        FaceInfo info = faces.findByUserId(owner.getUserId()).orElseThrow();
        assertThat(Path.of(info.getFilePath1()).getParent().getFileName().toString()).isEqualTo(response.taskId());
        assertThat(ImageIO.read(Path.of(info.getFilePath1()).toFile())).isNotNull();

        TaskQueueService restarted = new TaskQueueService(tasks, faces, users, storage, DEVICE);
        Map<String, Object> claimed = new TransactionTemplate(transactionManager)
                .execute(status -> restarted.getNextTask(DEVICE, owner.getUserNo()));
        assertThat(claimed).containsEntry("task_id", response.taskId()).containsEntry("user_no", owner.getUserNo());
        assertThat(tasks.findById(response.taskId()).orElseThrow().getStatus()).isEqualTo(FaceTaskStatus.RUNNING);
    }

    @Test
    void competingClaimsDeliverOneLeaseOnly() throws Exception {
        FaceTask queued = queued(owner());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return service.getNextTask(DEVICE, queued.getUserNo()); });
            var second = pool.submit(() -> { start.await(); return service.getNextTask(DEVICE, queued.getUserNo()); });
            start.countDown();
            Map<String, Object> a = first.get(15, TimeUnit.SECONDS);
            Map<String, Object> b = second.get(15, TimeUnit.SECONDS);
            assertThat((a == null ? 0 : 1) + (b == null ? 0 : 1)).isEqualTo(1);
        }
        assertThat(tasks.findById(queued.getTaskId()).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void optionalOwnerFilterDoesNotClaimAnotherUsersTask() {
        FaceTask first = queued(owner());
        FaceTask second = queued(owner());
        Map<String, Object> claim = service.getNextTask(DEVICE, second.getUserNo());
        assertThat(claim).containsEntry("task_id", second.getTaskId());
        assertThat(tasks.findById(first.getTaskId()).orElseThrow().getStatus()).isEqualTo(FaceTaskStatus.QUEUED);
    }

    @Test
    void expiredLeaseIsReclaimedAndStaleOrConflictingResultsCannotOverwriteDurableAck() {
        FaceTask task = queued(owner());
        String firstToken = token(service.getNextTask(DEVICE, task.getUserNo()));
        expire(task.getTaskId());
        String secondToken = token(service.getNextTask(DEVICE, task.getUserNo()));
        assertThat(secondToken).isNotEqualTo(firstToken);
        assertStatus(HttpStatus.CONFLICT, () -> service.recordResult(task.getTaskId(), "TRAIN", DEVICE, firstToken, "success", "stale"));
        service.recordResult(task.getTaskId(), "TRAIN", DEVICE, secondToken, "success", "complete");
        service.recordResult(task.getTaskId(), "TRAIN", DEVICE, secondToken, "success", "retry after lost ACK");
        FaceTask completed = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(FaceTaskStatus.SUCCEEDED);
        assertThat(completed.getMessage()).isEqualTo("complete");
        assertStatus(HttpStatus.CONFLICT, () -> service.recordResult(task.getTaskId(), "TRAIN", DEVICE, secondToken, "error", "changed result"));
    }

    @Test
    void threeExpiredDeliveriesBecomeFailedAndAreNeverClaimedAgain() {
        FaceTask task = queued(owner());
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(service.getNextTask(DEVICE, task.getUserNo())).isNotNull();
            expire(task.getTaskId());
        }
        assertThat(service.getNextTask(DEVICE, task.getUserNo())).isNull();
        FaceTask failed = tasks.findById(task.getTaskId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(FaceTaskStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getFinishedAt()).isNotNull();
    }

    @Test
    void heartbeatExtendsLeaseAndExpiredHeartbeatIsRejected() {
        FaceTask task = queued(owner());
        String token = token(service.getNextTask(DEVICE, task.getUserNo()));
        FaceTask running = tasks.findById(task.getTaskId()).orElseThrow();
        running.setLeaseExpiresAt(Instant.now().plusSeconds(5));
        tasks.saveAndFlush(running);
        service.renewLease(task.getTaskId(), DEVICE, token);
        assertThat(tasks.findById(task.getTaskId()).orElseThrow().getLeaseExpiresAt()).isAfter(Instant.now().plusSeconds(100));
        assertThat(service.getNextTask(DEVICE, task.getUserNo())).isNull();
        expire(task.getTaskId());
        assertStatus(HttpStatus.CONFLICT, () -> service.renewLease(task.getTaskId(), DEVICE, token));
        assertStatus(HttpStatus.CONFLICT, () -> service.recordResult(task.getTaskId(), "TRAIN", DEVICE, token, "success", "too late"));
    }

    @Test
    void ownershipAndWorkerIdentityAreCheckedBeforeReadingOrChangingTasks() {
        User owner = owner();
        User stranger = owner();
        FaceTask task = queued(owner);
        assertStatus(HttpStatus.NOT_FOUND, () -> service.getStatus(task.getTaskId(), stranger.getUserId()));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.getStatus(task.getTaskId(), "missing-owner"));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.getNextTask("different-device", null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.getNextTask(DEVICE, -1));
        assertThat(service.getStatus(task.getTaskId(), owner.getUserId()).status()).isEqualTo(FaceTaskStatus.QUEUED);
    }

    @Test
    void invalidImageAndMissingOwnerCreateNoDatabaseRows() throws Exception {
        User owner = owner();
        List<MultipartFile> invalid = List.of(image("file1"), new MockMultipartFile("file2", "bad.jpg", "image/jpeg", new byte[]{1, 2}), image("file3"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.registerFace(owner.getUserId(), invalid));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.registerFace("missing-owner", images()));
        assertThat(tasks.count()).isZero();
        assertThat(faces.count()).isZero();
    }

    @Test
    void databaseRollbackRemovesTheAlreadyStoredTaskImages() {
        User owner = owner();
        AtomicReference<Path> storedPath = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                service.registerFace(owner.getUserId(), images());
                storedPath.set(Path.of(faces.findByUserId(owner.getUserId()).orElseThrow().getFilePath1()));
                assertThat(Files.exists(storedPath.get())).isTrue();
                status.setRollbackOnly();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        assertThat(Files.exists(storedPath.get().getParent())).isFalse();
        assertThat(tasks.count()).isZero();
        assertThat(faces.count()).isZero();
    }

    @Test
    void httpContractReturnsAcceptedAndRequiresNestedResultWithLease() throws Exception {
        User owner = owner();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FaceRegisterController(service), new TaskController(service)).build();
        mvc.perform(multipart("/api/face/register").file(image("file1")).file(image("file2")).file(image("file3"))
                        .param("userId", owner.getUserId()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.taskId").isString())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        FaceTask task = tasks.findAll().getFirst();
        mvc.perform(get("/api/face/tasks/{id}", task.getTaskId()).param("userId", owner.getUserId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        String token = token(service.getNextTask(DEVICE, owner.getUserNo()));
        String body = """
                {"task_id":"%s","type":"TRAIN","device_id":"door-camera-a","lease_token":"%s",
                 "result":{"status":"success","message":"trained"}}
                """.formatted(task.getTaskId(), token);
        mvc.perform(post("/api/result").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"));
        mvc.perform(post("/api/result").contentType("application/json").content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/result").contentType("application/json").content("{\"result\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/get-task").param("device_id", DEVICE).param("user_no", owner.getUserNo().toString()))
                .andExpect(status().isNoContent());
    }

    private User owner() {
        return owner("face-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private User owner(String userId) {
        User user = new User();
        user.setUserId(userId);
        return users.saveAndFlush(user);
    }

    private FaceTask queued(User owner) {
        FaceTask task = new FaceTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setUserId(owner.getUserId());
        task.setUserNo(owner.getUserNo());
        task.setImageUrl1("/pictures/test/1.jpg");
        task.setImageUrl2("/pictures/test/2.jpg");
        task.setImageUrl3("/pictures/test/3.jpg");
        task.setStatus(FaceTaskStatus.QUEUED);
        task.setMessage("queued");
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return tasks.saveAndFlush(task);
    }

    private void expire(String taskId) {
        FaceTask task = tasks.findById(taskId).orElseThrow();
        task.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        tasks.saveAndFlush(task);
    }

    private static String token(Map<String, Object> task) {
        return (String) task.get("lease_token");
    }

    private static List<MultipartFile> images() throws Exception {
        return List.of(image("file1"), image("file2"), image("file3"));
    }

    private static MockMultipartFile image(String field) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile(field, "face.png", "image/png", bytes.toByteArray());
    }

    private static void assertStatus(HttpStatus status, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }
}
