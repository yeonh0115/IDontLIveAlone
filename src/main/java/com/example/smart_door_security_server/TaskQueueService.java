package com.example.smart_door_security_server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskQueueService {
    static final Duration LEASE_DURATION = Duration.ofSeconds(120);
    static final int MAX_ATTEMPTS = 3;
    private final FaceTaskRepository taskRepository;
    private final FaceInfoRepository faceInfoRepository;
    private final UserRepository userRepository;
    private final FaceImageStorage imageStorage;
    private final String allowedDeviceId;
    private final Clock clock;

    @Autowired
    public TaskQueueService(FaceTaskRepository taskRepository, FaceInfoRepository faceInfoRepository,
                            UserRepository userRepository, FaceImageStorage imageStorage,
                            @Value("${FACE_WORKER_DEVICE_ID:door-camera-a}") String allowedDeviceId) {
        this(taskRepository, faceInfoRepository, userRepository, imageStorage, allowedDeviceId, Clock.systemUTC());
    }

    TaskQueueService(FaceTaskRepository taskRepository, FaceInfoRepository faceInfoRepository,
                     UserRepository userRepository, FaceImageStorage imageStorage,
                     String allowedDeviceId, Clock clock) {
        this.taskRepository = taskRepository;
        this.faceInfoRepository = faceInfoRepository;
        this.userRepository = userRepository;
        this.imageStorage = imageStorage;
        this.allowedDeviceId = allowedDeviceId;
        this.clock = clock;
    }

    @Transactional(rollbackFor = IOException.class)
    public FaceTaskResponse registerFace(String userId, List<MultipartFile> files) throws IOException {
        requireUserId(userId);
        User owner = userRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        // Lock the owner so concurrent first registrations cannot create duplicate FaceInfo rows.
        owner = userRepository.findForUpdateByUserNo(owner.getUserNo())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        String taskId = UUID.randomUUID().toString();
        Path directory = imageStorage.save(taskId, files);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) imageStorage.delete(directory);
                }
            });
        }
        try {
            Instant now = clock.instant();
            FaceTask task = new FaceTask();
            task.setTaskId(taskId);
            task.setUserId(owner.getUserId());
            task.setUserNo(owner.getUserNo());
            task.setImageUrl1("/pictures/" + taskId + "/face1.jpg");
            task.setImageUrl2("/pictures/" + taskId + "/face2.jpg");
            task.setImageUrl3("/pictures/" + taskId + "/face3.jpg");
            task.setStatus(FaceTaskStatus.QUEUED);
            task.setMessage("얼굴 학습 요청이 대기 중입니다.");
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            FaceInfo faceInfo = faceInfoRepository.findByUserId(owner.getUserId()).orElseGet(FaceInfo::new);
            faceInfo.setUserId(owner.getUserId());
            faceInfo.setFilePath1(directory.resolve("face1.jpg").toString());
            faceInfo.setFilePath2(directory.resolve("face2.jpg").toString());
            faceInfo.setFilePath3(directory.resolve("face3.jpg").toString());
            faceInfo.setUpdatedAt(LocalDateTime.ofInstant(now, clock.getZone()));
            faceInfoRepository.save(faceInfo);
            taskRepository.save(task);
            return FaceTaskResponse.from(task);
        } catch (RuntimeException ex) {
            imageStorage.delete(directory);
            throw ex;
        }
    }

    @Transactional
    public Map<String, Object> getNextTask(String deviceId, Integer userNo) {
        requireDevice(deviceId);
        return claim(deviceId, userNo, false);
    }

    @Transactional
    public Map<String, Object> getNextTask(DeviceRegistrationService.DeviceIdentity camera) {
        return claim(camera.deviceId(), camera.userNo(), true);
    }

    private Map<String, Object> claim(String deviceId, Integer userNo, boolean paired) {
        if (userNo != null && userNo <= 0) throw badRequest("user_no가 올바르지 않습니다.");
        Instant now = clock.instant();
        while (true) {
            List<FaceTask> candidates = taskRepository.findClaimable(FaceTaskStatus.QUEUED,
                    FaceTaskStatus.RUNNING, now, userNo, paired, PageRequest.of(0, 1));
            if (candidates.isEmpty()) return null;
            FaceTask task = candidates.getFirst();
            if (!imageStorage.hasImages(task.getTaskId())) {
                failExpiredTask(task, now);
                task.setMessage("임시 얼굴 사진이 만료되었습니다. 사진을 다시 등록해 주세요.");
                taskRepository.saveAndFlush(task);
                continue;
            }
            if (task.getAttempts() >= MAX_ATTEMPTS) {
                failExpiredTask(task, now);
                taskRepository.saveAndFlush(task);
                continue;
            }
            task.setAttempts(task.getAttempts() + 1);
            task.setStatus(FaceTaskStatus.RUNNING);
            task.setDeviceId(deviceId);
            task.setLeaseToken(UUID.randomUUID().toString());
            task.setLeaseExpiresAt(now.plus(LEASE_DURATION));
            task.setUpdatedAt(now);
            task.setMessage("라즈베리파이에서 얼굴을 학습하고 있습니다.");
            taskRepository.save(task);
            return Map.of("task_id", task.getTaskId(), "type", "TRAIN", "user_id", task.getUserId(),
                    "user_no", task.getUserNo(), "image_urls", task.imageUrls(), "lease_token", task.getLeaseToken());
        }
    }

    @Transactional
    public FaceTaskResponse getStatus(String taskId, String userId) {
        requireUserId(userId);
        User owner = userRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다."));
        FaceTask task = getLockedTask(taskId);
        if (!task.getUserId().equals(userId) || !task.getUserNo().equals(owner.getUserNo())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다.");
        }
        Instant now = clock.instant();
        if (task.getStatus() == FaceTaskStatus.RUNNING && task.getAttempts() >= MAX_ATTEMPTS
                && !task.getLeaseExpiresAt().isAfter(now)) failExpiredTask(task, now);
        return FaceTaskResponse.from(task);
    }

    @Transactional
    public void renewLease(String taskId, String deviceId, String leaseToken) {
        requireDevice(deviceId);
        renew(taskId, deviceId, leaseToken, null);
    }

    @Transactional
    public void renewLease(String taskId, DeviceRegistrationService.DeviceIdentity camera, String leaseToken) {
        renew(taskId, camera.deviceId(), leaseToken, camera.userNo());
    }

    private void renew(String taskId, String deviceId, String leaseToken, Integer owner) {
        FaceTask task = getLockedTask(taskId);
        requireOwner(task, owner);
        requireLease(task, deviceId, leaseToken);
        Instant now = clock.instant();
        if (task.getStatus() != FaceTaskStatus.RUNNING || !task.getLeaseExpiresAt().isAfter(now)) {
            throw conflict("학습 작업의 임대가 만료되었거나 이미 종료되었습니다.");
        }
        task.setLeaseExpiresAt(now.plus(LEASE_DURATION));
        task.setUpdatedAt(now);
    }

    @Transactional
    public void recordResult(String taskId, String type, String deviceId, String leaseToken,
                             String resultStatus, String message) {
        requireDevice(deviceId);
        record(taskId, type, deviceId, leaseToken, resultStatus, message, null);
    }

    @Transactional
    public void recordResult(String taskId, String type, DeviceRegistrationService.DeviceIdentity camera,
            String leaseToken, String resultStatus, String message) {
        record(taskId, type, camera.deviceId(), leaseToken, resultStatus, message, camera.userNo());
    }

    private void record(String taskId, String type, String deviceId, String leaseToken,
            String resultStatus, String message, Integer owner) {
        if (!"TRAIN".equals(type)) throw badRequest("지원하지 않는 작업 종류입니다.");
        if (!"success".equals(resultStatus) && !"error".equals(resultStatus)) {
            throw badRequest("result.status는 success 또는 error여야 합니다.");
        }
        FaceTask task = getLockedTask(taskId);
        requireOwner(task, owner);
        requireLease(task, deviceId, leaseToken);
        if (task.getStatus() == FaceTaskStatus.SUCCEEDED || task.getStatus() == FaceTaskStatus.FAILED) {
            if (resultStatus.equals(task.getResultStatus())) return;
            throw conflict("이미 종료된 작업에 다른 결과를 기록할 수 없습니다.");
        }
        Instant now = clock.instant();
        if (task.getStatus() != FaceTaskStatus.RUNNING || !task.getLeaseExpiresAt().isAfter(now)) {
            throw conflict("학습 작업의 임대가 만료되었습니다.");
        }
        task.setStatus("success".equals(resultStatus) ? FaceTaskStatus.SUCCEEDED : FaceTaskStatus.FAILED);
        task.setResultStatus(resultStatus);
        task.setMessage(resultMessage(resultStatus, message));
        task.setUpdatedAt(now);
        task.setFinishedAt(now);
        deleteImagesAfterCommit(task.getTaskId());
        // The controller responds only after this transaction has durably committed.
    }

    private FaceTask getLockedTask(String taskId) {
        if (taskId == null || taskId.length() != 36) throw badRequest("task_id가 올바르지 않습니다.");
        return taskRepository.findForUpdate(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다."));
    }

    private void requireDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 100
                || allowedDeviceId == null || allowedDeviceId.isBlank() || !allowedDeviceId.equals(deviceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "허용되지 않은 얼굴 학습 장치입니다.");
        }
    }

    private static void requireLease(FaceTask task, String deviceId, String token) {
        if (token == null || token.isBlank() || !token.equals(task.getLeaseToken())
                || !deviceId.equals(task.getDeviceId())) throw conflict("현재 작업의 임대 정보와 일치하지 않습니다.");
    }

    private static void requireOwner(FaceTask task, Integer owner) {
        if (owner != null && !owner.equals(task.getUserNo())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 계정의 학습 작업입니다.");
        }
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > 50) throw badRequest("userId가 올바르지 않습니다.");
    }

    private static String resultMessage(String status, String message) {
        String value = message == null || message.isBlank()
                ? ("success".equals(status) ? "얼굴 학습이 완료되었습니다." : "얼굴 학습에 실패했습니다.")
                : message.strip();
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private void failExpiredTask(FaceTask task, Instant now) {
        task.setStatus(FaceTaskStatus.FAILED);
        task.setMessage("라즈베리파이 응답을 받지 못해 학습이 종료되었습니다. 연결을 확인하고 다시 등록해 주세요.");
        task.setUpdatedAt(now);
        task.setFinishedAt(now);
        deleteImagesAfterCommit(task.getTaskId());
    }

    private void deleteImagesAfterCommit(String taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { imageStorage.deleteTask(taskId); }
            });
        } else imageStorage.deleteTask(taskId);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
