package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/face")
@RequiredArgsConstructor
public class FaceRegisterController {

    private final FaceInfoRepository faceInfoRepository;
    private final TaskQueueService taskQueueService;
    
    // 리눅스(Render 클라우드)와 윈도우 환경 모두에서 동작하도록 상대 경로 사용
    private final String UPLOAD_DIR = "./pictures/";

    @PostMapping("/register")
    public ResponseEntity<String> registerFace(
            @RequestParam("userId") String userId,
            @RequestParam("file1") MultipartFile file1,
            @RequestParam("file2") MultipartFile file2,
            @RequestParam("file3") MultipartFile file3) {

        try {
            Path userPath = Paths.get(UPLOAD_DIR, userId);
            if (!Files.exists(userPath)) {
                Files.createDirectories(userPath);
            }

            String timestamp = String.valueOf(System.currentTimeMillis());
            String fileName1 = "face1_" + timestamp + ".jpg";
            String fileName2 = "face2_" + timestamp + ".jpg";
            String fileName3 = "face3_" + timestamp + ".jpg";

            // 파일 저장 로직
            String path1 = saveFile(file1, userPath, fileName1);
            String path2 = saveFile(file2, userPath, fileName2);
            String path3 = saveFile(file3, userPath, fileName3);

            // DB 저장 로직
            FaceInfo faceInfo = faceInfoRepository.findByUserId(userId).orElse(new FaceInfo());
            faceInfo.setUserId(userId);
            faceInfo.setFilePath1(path1);
            faceInfo.setFilePath2(path2);
            faceInfo.setFilePath3(path3);
            faceInfo.setUpdatedAt(LocalDateTime.now());
            faceInfoRepository.save(faceInfo);

            // 현재 서버의 기본 주소 획득 (예: https://idontlivealone.onrender.com)
            String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            
            // 파이썬 코드가 다운로드할 수 있는 웹 URL 생성
            String url1 = baseUrl + "/pictures/" + userId + "/" + fileName1;
            String url2 = baseUrl + "/pictures/" + userId + "/" + fileName2;
            String url3 = baseUrl + "/pictures/" + userId + "/" + fileName3;
            List<String> imageUrls = Arrays.asList(url1, url2, url3);

            // 🔥 [핵심]: 라즈베리파이로 직접 쏘지 않고, 큐에 작업을 올림 (라즈베리파이가 1초 뒤에 알아서 가져감)
            taskQueueService.addTrainTask(userId, imageUrls);

            return ResponseEntity.ok("success");

        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("fail: " + e.getMessage());
        }
    }

    private String saveFile(MultipartFile file, Path userPath, String fileName) throws IOException {
        if (file.isEmpty()) return "";
        Path filePath = userPath.resolve(fileName);
        file.transferTo(filePath.toFile());
        return filePath.toString(); // DB 저장을 위한 상대경로 반환
    }
}
