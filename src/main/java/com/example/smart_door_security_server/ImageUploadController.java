package com.example.smart_door_security_server;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api")
public class ImageUploadController {

    // 이미지가 실제로 저장될 렌더 서버 내부의 디렉토리 경로 정의
    // 스프링 부트의 기본 static 폴더 내부에 저장하여 외부에 자동 노출되게 합니다.
    private static final String UPLOAD_DIR = "src/main/resources/static/uploads/";

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("log_id") String logId,
            @RequestParam("date") String date) {

        // 1. 파일이 비어있는지 확인
        if (file.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "업로드할 파일이 비어 있습니다.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }

        try {
            // 2. 저장 폴더가 없으면 생성
            File uploadDirFile = new File(UPLOAD_DIR);
            if (!uploadDirFile.exists()) {
                uploadDirFile.mkdirs();
            }

            // 3. 파일명 추출 및 안전한 저장 경로 빌드
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                originalFilename = "event_" + logId + ".jpg";
            }
            
            Path filepath = Paths.get(UPLOAD_DIR, originalFilename);
            
            // 4. 서버 디스크에 파일 물리적 저장
            Files.write(filepath, file.getBytes());

            // 5. 외부에서 접속 가능한 도메인 기반 이미지 URL 동적 생성
            // 렌더 서버 주소 뒤에 /uploads/파일명 형태로 접근하게 됩니다.
            // (예: https://idontlivealone.onrender.com/uploads/event_12_2026-07-15.jpg)
            // 배포 환경이므로 프로토콜과 호스트를 상대 경로로 매핑하도록 세팅합니다.
            String fileDownloadUri = "/uploads/" + originalFilename;

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            // 렌더 도메인과 결합될 수 있도록 상대 경로를 반환합니다. 
            // 만약 감시 코드(PC)에서 절대 경로를 원한다면, 렌더 주소를 앞에 하드코딩해 주셔도 좋습니다.
            response.put("url", "https://idontlivealone.onrender.com" + fileDownloadUri);
            response.put("filename", originalFilename);

            System.out.println("📸 [스프링 부트 업로드 성공] Log ID: " + logId + " -> URL: " + response.get("url"));

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            System.err.println("🚨 파일 저장 중 서버 에러 발생: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "서버 내부 파일 저장 에러: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}
