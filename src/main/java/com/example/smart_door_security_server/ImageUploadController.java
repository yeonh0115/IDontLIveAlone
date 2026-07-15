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

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor // 📌 의존성 주입을 위해 추가
@RequestMapping("/api")
public class ImageUploadController {

    // 📌 리포트 업데이트를 위해 리포지토리 의존성 주입 추가
    private final DailyReportRepository dailyReportRepository;
    private final UserRepository userRepository;

    private static final String UPLOAD_DIR = "src/main/resources/static/uploads/";

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("log_id") String logId,
            @RequestParam("date") String date) { // 파이썬에서 날짜 포맷은 "2026-07-15" 형태로 와야 합니다.

        if (file.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "업로드할 파일이 비어 있습니다.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }

        try {
            File uploadDirFile = new File(UPLOAD_DIR);
            if (!uploadDirFile.exists()) {
                uploadDirFile.mkdirs();
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                originalFilename = "event_" + logId + ".jpg";
            }
            
            Path filepath = Paths.get(UPLOAD_DIR, originalFilename);
            Files.write(filepath, file.getBytes());

            String fileDownloadUri = "/uploads/" + originalFilename;
            String fullImageUrl = "https://idontlivealone.onrender.com" + fileDownloadUri;

            // ================== 📌 [핵심 연동 코드 추가] ==================
            try {
                // 1. 파이썬이 넘겨준 날짜 정보 파싱 ("2026-07-15" -> LocalDate)
                LocalDate parsedDate = LocalDate.parse(date);

                // 2. 임의의 테스트 유저 1번 조회 (또는 상황에 맞게 유저 설정)
                User user = userRepository.findById(1)
                        .orElseGet(() -> userRepository.findAll().stream().findFirst().orElse(null));

                if (user != null) {
                    // 3. 해당 유저의 해당 날짜 리포트가 이미 존재하는지 조회
                    Optional<DailyReport> existingReport = dailyReportRepository.findByUserAndReportDate(user, parsedDate);

                    if (existingReport.isPresent()) {
                        // 이미 리포트가 있다면 사진 주소만 업데이트
                        DailyReport report = existingReport.get();
                        report.setPhotoUrl(fullImageUrl);
                        dailyReportRepository.save(report);
                        System.out.println("💾 [리포트 업데이트 성공] 기존 리포트에 사진 추가 완료: " + fullImageUrl);
                    } else {
                        // 만약 그날 리포트 데이터가 아예 없다면 임시로 하나 생성해서 사진 저장
                        DailyReport newReport = new DailyReport();
                        newReport.setUser(user);
                        newReport.setReportDate(parsedDate);
                        newReport.setTotalEvents(1);
                        newReport.setHighRiskEvents(1); // 경고 이미지가 찍혔으므로 위험 1 부여
                        newReport.setReportText("**주의: 보안 경고 발생**\n문 열림 시도가 감지되었습니다.");
                        newReport.setPhotoUrl(fullImageUrl);
                        dailyReportRepository.save(newReport);
                        System.out.println("💾 [리포트 생성 성공] 새 리포트 생성 및 사진 저장 완료: " + fullImageUrl);
                    }
                }
            } catch (Exception dbEx) {
                System.err.println("🚨 [DB 연동 실패] 리포트에 이미지 URL을 업데이트하지 못했습니다: " + dbEx.getMessage());
                // 파일 업로드 자체가 실패한 것은 아니므로, 업로드 처리는 정상 진행합니다.
            }
            // ==============================================================

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("url", fullImageUrl);
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
