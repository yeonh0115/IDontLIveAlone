package com.example.smart_door_security_server;

import com.example.smart_door_security_server.FaceInfo;
import com.example.smart_door_security_server.FaceInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/face")
@RequiredArgsConstructor
public class FaceRegisterController {

    private final FaceInfoRepository faceInfoRepository;
    private final String UPLOAD_DIR = "C:\\2026 mdp\\pictures\\";

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
            String path1 = saveFile(file1, userPath, "face1_" + timestamp + ".jpg");
            String path2 = saveFile(file2, userPath, "face2_" + timestamp + ".jpg");
            String path3 = saveFile(file3, userPath, "face3_" + timestamp + ".jpg");

            FaceInfo faceInfo = faceInfoRepository.findByUserId(userId).orElse(new FaceInfo());
            faceInfo.setUserId(userId);
            faceInfo.setFilePath1(path1);
            faceInfo.setFilePath2(path2);
            faceInfo.setFilePath3(path3);
            faceInfo.setUpdatedAt(LocalDateTime.now());

            faceInfoRepository.save(faceInfo);

            new Thread(() -> sendTriggerToRaspberryPi(userId)).start();

            return ResponseEntity.ok("success");

        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("fail: " + e.getMessage());
        }
    }

    private String saveFile(MultipartFile file, Path userPath, String fileName) throws IOException {
        if (file.isEmpty()) return "";
        Path filePath = userPath.resolve(fileName);
        file.transferTo(filePath.toFile());
        return filePath.toString();
    }

    private void sendTriggerToRaspberryPi(String userId) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String raspberryPiUrl = "http://192.168.137.115:5000/api/train";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> bodyMap = new HashMap<>();
            bodyMap.put("userId", userId);

            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(bodyMap, headers);
            restTemplate.postForEntity(raspberryPiUrl, requestEntity, String.class);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
