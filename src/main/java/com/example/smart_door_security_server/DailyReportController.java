package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reports")
public class DailyReportController {

    private final DailyReportRepository dailyReportRepository;
    private final UserRepository userRepository;

    @GetMapping("/{userNo}")
    public List<DailyReport> getUserReports(@PathVariable Integer userNo) {
        // 📌 수정: 모든 리포트를 다 가져오는 대신, 접속한 userNo의 리포트만 걸러서 반환합니다.
        // JPA 영속성 프레임워크(LAZY 로딩) 오류 방지를 위해 프록시 순환참조를 끊고 전송합니다.
        return dailyReportRepository.findAllReports().stream()
                .filter(report -> report.getUser().getUserNo().equals(userNo))
                .map(report -> {
                    // 순환 참조 및 직렬화 에러를 차단하기 위해 임시로 연관관계 필드를 비웁니다.
                    report.setUser(null); 
                    return report;
                })
                .collect(Collectors.toList());
    }

    @GetMapping("/setup-test")
    public String setupTest() {
        User testUser = userRepository.findById(1)
                .orElseGet(() -> userRepository.findAll().stream().findFirst().orElse(null));

        if (testUser == null) {
            return "실패: DB에 등록된 유저가 없습니다. 앱에서 먼저 회원가입을 해주세요.";
        }

        try {
            dailyReportRepository.deleteAll();

            for (int i = 1; i <= 5; i++) {
                DailyReport report = new DailyReport();
                report.setUser(testUser);
                report.setReportDate(LocalDate.now().minusDays(i));
                report.setTotalEvents(10 + i * 2);
                report.setHighRiskEvents(i % 2);
                report.setReportText(i + "일 전 가구 보안 리포트입니다.");
                // 테스트 시 더미 이미지 주소를 넣어둡니다.
                report.setPhotoUrl("https://idontlivealone.onrender.com/uploads/test_image.jpg");

                dailyReportRepository.save(report);
            }
            return "성공: 데이터 생성 완료!";
        } catch (Exception e) {
            return "오류 발생: " + e.getMessage();
        }
    }
}
