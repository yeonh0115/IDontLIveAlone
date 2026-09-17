package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.example.smart_door_security_server.CameraCaptureTask.Status.*;

@Service @RequiredArgsConstructor
public class CameraCaptureService {
    private final CameraCaptureTaskRepository captures;
    private final PairedDeviceRepository devices;
    private final UserRepository users;
    private final EventPhotoRepository photos;
    private final EventPhotoSettings settings;
    public record CaptureState(boolean cameraCaptureQueued, String captureTaskId, String captureStatus) {
        static CaptureState absent() { return new CaptureState(false, null, "NOT_REQUESTED"); }
    }
    public record CaptureLease(String captureTaskId, String eventId, Integer userNo, LocalDate eventDate,
            Instant expiresAt, String leaseToken) { }

    // Called with the event owner's row locked; duplicate event submissions use the same task.
    @Transactional(propagation=Propagation.MANDATORY)
    public CaptureState request(Integer userNo, String eventId, LocalDate eventDate, Instant occurredAt, boolean pairedSensor) {
        if (!settings.isEnabled()) return CaptureState.absent();
        CameraCaptureTask existing = captures.findByUserNoAndEventId(userNo, eventId).orElse(null);
        if (existing != null) {
            if ((existing.getStatus() == QUEUED || existing.getStatus() == RUNNING)
                    && !existing.getExpiresAt().isAfter(Instant.now())) existing.setStatus(EXPIRED);
            return state(existing);
        }
        if (!pairedSensor) return CaptureState.absent();
        if (photos.findByUserNoAndSourceEventId(userNo, eventId).isPresent()) {
            return new CaptureState(false, null, "SUCCEEDED");
        }
        Instant now = Instant.now();
        Instant expiresAt = occurredAt.plusSeconds(120);
        if (!expiresAt.isAfter(now)) return new CaptureState(false, null, "EXPIRED");
        // A clock set in the future must not create an unbounded capture window.
        if (expiresAt.isAfter(now.plusSeconds(120))) expiresAt = now.plusSeconds(120);
        PairedDevice camera = devices.findByUserNoAndRole(userNo, DeviceRole.CAMERA).orElse(null);
        if (camera == null) return CaptureState.absent();
        CameraCaptureTask task = new CameraCaptureTask();
        task.setCaptureTaskId(UUID.randomUUID().toString()); task.setUserNo(userNo);
        task.setCameraDeviceId(camera.getDeviceId()); task.setEventId(eventId); task.setEventDate(eventDate);
        task.setCreatedAt(now); task.setExpiresAt(expiresAt); task.setStatus(QUEUED);
        captures.saveAndFlush(task);
        return state(task);
    }

    @Transactional
    public CaptureLease claim(DeviceRegistrationService.DeviceIdentity camera) {
        if (!settings.isEnabled()) return null;
        lockOwner(camera.userNo());
        Instant now = Instant.now();
        while (true) {
            var candidates = captures.findClaimable(camera.deviceId(), camera.userNo(), QUEUED, RUNNING, now, PageRequest.of(0, 1));
            if (candidates.isEmpty()) return null;
            CameraCaptureTask task = candidates.getFirst();
            if (!task.getExpiresAt().isAfter(now)) { task.setStatus(EXPIRED); captures.flush(); continue; }
            if (task.getAttempts() >= 3) { task.setStatus(FAILED); captures.flush(); continue; }
            task.setStatus(RUNNING); task.setAttempts(task.getAttempts() + 1);
            task.setLeaseToken(UUID.randomUUID().toString());
            task.setLeaseUntil(now.plusSeconds(30).isBefore(task.getExpiresAt()) ? now.plusSeconds(30) : task.getExpiresAt());
            return new CaptureLease(task.getCaptureTaskId(), task.getEventId(), task.getUserNo(), task.getEventDate(),
                    task.getExpiresAt(), task.getLeaseToken());
        }
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public CameraCaptureTask validatePhoto(DeviceRegistrationService.DeviceIdentity camera, String taskId,
            String leaseToken, String eventId) {
        CameraCaptureTask task = locked(camera, taskId, leaseToken);
        if (!task.getEventId().equals(eventId)) throw conflict("이벤트와 촬영 작업이 일치하지 않습니다.");
        if (task.getStatus() != SUCCEEDED) requireLive(task);
        return task;
    }

    @Transactional
    public void result(DeviceRegistrationService.DeviceIdentity camera, String taskId, String leaseToken,
            String status, String message) {
        if (!"success".equals(status) && !"error".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status는 success 또는 error여야 합니다.");
        }
        lockOwner(camera.userNo());
        CameraCaptureTask task = locked(camera, taskId, leaseToken);
        if ((task.getStatus() == SUCCEEDED && "success".equals(status))
                || (task.getStatus() == FAILED && "error".equals(status))) return;
        requireLive(task);
        if ("success".equals(status)) throw conflict("사진 업로드가 완료된 후 성공을 확인할 수 있습니다.");
        task.setStatus(FAILED);
        task.setMessage(message == null ? null : message.substring(0, Math.min(500, message.length())));
    }

    private CameraCaptureTask locked(DeviceRegistrationService.DeviceIdentity camera, String taskId, String token) {
        CameraCaptureTask task = captures.findForUpdate(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "촬영 작업을 찾을 수 없습니다."));
        if (!camera.userNo().equals(task.getUserNo()) || !camera.deviceId().equals(task.getCameraDeviceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 장치의 촬영 작업입니다.");
        }
        if (token == null || !token.equals(task.getLeaseToken())) throw conflict("촬영 임대가 일치하지 않습니다.");
        return task;
    }
    private void requireLive(CameraCaptureTask task) {
        Instant now = Instant.now();
        if (task.getStatus() != RUNNING || task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now)
                || !task.getExpiresAt().isAfter(now)) throw conflict("촬영 임대가 만료되었거나 작업이 종료되었습니다.");
    }
    void lockOwner(Integer owner) {
        users.findForUpdateByUserNo(owner).orElseThrow(TokenSecrets::unauthorized);
    }
    private static CaptureState state(CameraCaptureTask task) {
        return new CaptureState(true, task.getCaptureTaskId(), task.getStatus().name());
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
