package com.example.smart_door_security_server;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service @RequiredArgsConstructor
public class CameraPhotoService {
    private final CameraCaptureService captures;
    private final PhotoUploadService photos;
    @Transactional
    public PhotoUploadService.UploadResult save(DeviceRegistrationService.DeviceIdentity camera, MultipartFile file,
            String eventId, String taskId, String leaseToken) {
        captures.lockOwner(camera.userNo());
        CameraCaptureTask task = captures.validatePhoto(camera, taskId, leaseToken, eventId);
        var result = photos.save(file, camera.userNo(), eventId, task.getEventDate());
        task.setStatus(CameraCaptureTask.Status.SUCCEEDED);
        return result;
    }
}
