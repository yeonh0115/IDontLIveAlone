package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ImageUploadController {

    private final DailyReportRepository dailyReportRepository;
    private final UserRepository userRepository;

    // 📌 Render 서버 배포 환경(JAR 실행) 지원을 위해 실행 위치 기준 동적 경로 설정
    private static final String UPLOAD_DIR = System.getProperty("user.dir") + File.separator + "uploads" + File.separator;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("log_id") String logId,
            @RequestParam("date") String date) {

        if (file.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "업로드할 파일이 비어 있습니다.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }

        try {
            // 업로드 폴더가 없으면 생성
            File uploadDirFile = new File(UPLOAD_DIR);
            if (!uploadDirFile.exists()) {
                uploadDirFile.mkdirs();
            }

            // 파일명 설정 및 UUID를 통한 파일 중복 덮어쓰기 방지
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isEmpty()) {
                originalFilename = "event_" + logId + ".jpg";
            }
            
            String storeFilename = UUID.randomUUID().toString().substring(0, 8) + "_" + originalFilename;
            
            // 파일 저장
            Path filepath = Paths.get(UPLOAD_DIR, storeFilename);
            Files.write(filepath, file.getBytes());

            String fileDownloadUri = "/uploads/" + storeFilename;
            String fullImageUrl = "https://idontlivealone.onrender.com" + fileDownloadUri;

            // ================== 📌 [DB 연동 및 DailyReport 업데이트] ==================
            try {
                LocalDate parsedDate = LocalDate.parse(date);

                // 테스트용 사용자 (ID 1번 우선 조회, 없으면 첫 번째 유저 선택)
                User user = userRepository.findById(1)
                        .orElseGet(() -> userRepository.findAll().stream().findFirst().orElse(null));

                if (user != null) {
                    Optional<DailyReport> existingReport = dailyReportRepository.findByUserAndReportDate(user, parsedDate);

                    if (existingReport.isPresent()) {
                        // 기존 리포트가 있으면 사진 URL 덮어쓰기/업데이트
                        DailyReport report = existingReport.get();
                        report.setPhotoUrl(fullImageUrl);
                        dailyReportRepository.save(report);
                        System.out.println("💾 [리포트 업데이트 성공] 기존 리포트에 사진 추가: " + fullImageUrl);
                    } else {
                        // 해당 일자 리포트가 없으면 신규 생성
                        DailyReport newReport = new DailyReport();
                        newReport.setUser(user);
                        newReport.setReportDate(parsedDate);
                        newReport.setTotalEvents(1);
                        newReport.setHighRiskEvents(1);
                        newReport.setReportText("**주의: 보안 경고 발생**\n문 열림 시도가 감지되었습니다.");
                        newReport.setPhotoUrl(fullImageUrl);
                        dailyReportRepository.save(newReport);
                        System.out.println("💾 [리포트 생성 성공] 새 리포트 생성 및 사진 저장: " + fullImageUrl);
                    }
                }
            } catch (Exception dbEx) {
                System.err.println("🚨 [DB 연동 실패]: " + dbEx.getMessage());
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("url", fullImageUrl);
            response.put("filename", storeFilename);

            System.out.println("📸 [업로드 성공] Log ID: " + logId + " -> URL: " + fullImageUrl);

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            System.err.println("🚨 파일 저장 실패: " + e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "서버 내부 파일 저장 에러: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}
