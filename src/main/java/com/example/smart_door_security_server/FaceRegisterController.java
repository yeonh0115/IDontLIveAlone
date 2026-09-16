package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/face")
@RequiredArgsConstructor
public class FaceRegisterController {

    private final TaskQueueService taskQueueService;

    @PostMapping("/register")
    public ResponseEntity<FaceTaskResponse> registerFace(
            @RequestParam("userId") String userId,
            @RequestParam("file1") MultipartFile file1,
            @RequestParam("file2") MultipartFile file2,
            @RequestParam("file3") MultipartFile file3) {

        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(taskQueueService.registerFace(userId, List.of(file1, file2, file3)));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "얼굴 사진을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }
}
