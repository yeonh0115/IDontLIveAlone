package com.example.smart_door_security_server;

import com.example.smart_door_security_server.domain.DailyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface DailyReportRepository extends JpaRepository<DailyReport, Integer> {
    @Query("SELECT d FROM DailyReport d ORDER BY d.reportDate DESC")
    List<DailyReport> findAllReports();
}
