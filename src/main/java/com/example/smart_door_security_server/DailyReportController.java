package com.example.smart_door_security_server;

import com.example.smart_door_security_server.DailyReport;
import com.example.smart_door_security_server.User;
import com.example.smart_door_security_server.DailyReportRepository;
import com.example.smart_door_security_server.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reports")
public class DailyReportController {

    private final DailyReportRepository dailyReportRepository;
    private final UserRepository userRepository;

    @GetMapping("/{userNo}")
    public List<DailyReport> getUserReports(@PathVariable Integer userNo) {
        return dailyReportRepository.findAllReports();
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

                dailyReportRepository.save(report);
            }
            return "성공: 데이터 생성 완료!";
        } catch (Exception e) {
            return "오류 발생: " + e.getMessage();
        }
    }
}
