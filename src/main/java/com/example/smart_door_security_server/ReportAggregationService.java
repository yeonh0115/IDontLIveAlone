package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportAggregationService {
    private final DailyReportRepository reports;
    private final IntegratedLogRepository logs;

    // All callers must hold the owner's row lock in the same transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public DailyReport refresh(User user, LocalDate date) {
        List<IntegratedLog> dayLogs = logs.findByUserNoAndCreatedAtBetweenOrderByCreatedAtDesc(
                user.getUserNo(), date.atStartOfDay(), date.atTime(LocalTime.MAX));
        DailyReport report = reports.findByUserAndReportDate(user, date).orElseGet(() -> {
            DailyReport created = new DailyReport();
            created.setUser(user);
            created.setReportDate(date);
            created.setReportText("감지된 이벤트가 저장되었습니다. 상세 로그를 확인해 주세요.");
            return created;
        });
        report.setTotalEvents(dayLogs.size());
        report.setHighRiskEvents((int) dayLogs.stream()
                .filter(log -> log.getSeverity() == IntegratedLog.Severity.high).count());
        return reports.save(report);
    }
}
