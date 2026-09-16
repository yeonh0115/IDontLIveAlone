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

    @PostMapping("/upload")
    public Map<String, Object> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userNo") Integer userNo,
            @RequestParam("log_id") String logId,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        PhotoUploadService.UploadResult result = photoUploadService.save(file, userNo, logId, date);
        return Map.of("success", true, "url", result.url(),
                "filename", result.filename(), "duplicate", result.duplicate());
    }
}
