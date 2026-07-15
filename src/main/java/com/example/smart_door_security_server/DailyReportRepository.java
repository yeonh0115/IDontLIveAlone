package com.example.smart_door_security_server;

import com.example.smart_door_security_server.DailyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReport, Integer> {
    @Query("SELECT d FROM DailyReport d ORDER BY d.reportDate DESC")
    List<DailyReport> findAllReports();

    // 📌 [추가] 특정 유저와 특정 날짜로 리포트를 찾아주는 기능
    Optional<DailyReport> findByUserAndReportDate(User user, LocalDate reportDate);
}
