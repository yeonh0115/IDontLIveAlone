package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ImageUploadController {
    private final PhotoUploadService photoUploadService;
    private final DeviceRegistrationService devices;
    private final CameraPhotoService cameraPhotos;
    private final EventPhotoSettings settings;

    @PostMapping("/upload")
    public Map<String, Object> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value="userNo", required=false) Integer userNo,
            @RequestParam("log_id") String logId,
            @RequestParam(value="date", required=false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value="captureTaskId", required=false) String captureTaskId,
            @RequestParam(value="leaseToken", required=false) String leaseToken,
            @RequestHeader(value="Authorization", required=false) String authorization) {
        settings.requireEnabled();
        PhotoUploadService.UploadResult result;
        if (authorization != null) {
            var camera = devices.require(authorization, DeviceRole.CAMERA, userNo);
            result = captureTaskId == null ? photoUploadService.save(file, camera.userNo(), logId, date)
                    : cameraPhotos.save(camera, file, logId, captureTaskId, leaseToken);
        } else {
            if (captureTaskId != null || devices.hasRole(userNo, DeviceRole.CAMERA)) throw TokenSecrets.unauthorized();
            result = photoUploadService.save(file, userNo, logId, date);
        }
        return Map.of("success", true, "url", result.url(),
                "filename", result.filename(), "duplicate", result.duplicate());
    }
}
