package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reports")
public class DailyReportController {
    private final DailyReportRepository dailyReportRepository;

    // Keep the database owner intact: return a DTO instead of mutating a managed entity.
    public record ReportResponse(Integer id, LocalDate reportDate, Integer totalEvents,
            Integer highRiskEvents, String reportText, String photoUrl, LocalDateTime createdAt) {}

    @GetMapping("/{userNo}")
    public List<ReportResponse> getUserReports(@PathVariable Integer userNo) {
        return dailyReportRepository.findByUser_UserNoOrderByReportDateDesc(userNo).stream()
                .map(report -> new ReportResponse(report.getId(), report.getReportDate(),
                        report.getTotalEvents(), report.getHighRiskEvents(), report.getReportText(),
                        report.getPhotoUrl(), report.getCreatedAt()))
                .toList();
    }
}
