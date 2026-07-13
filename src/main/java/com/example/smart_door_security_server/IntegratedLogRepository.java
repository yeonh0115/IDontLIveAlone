package com.example.smart_door_security_server.repository;

import com.example.smart_door_security_server.domain.IntegratedLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface IntegratedLogRepository extends JpaRepository<IntegratedLog, Integer> {
    List<IntegratedLog> findByUserNoAndCreatedAtBetweenOrderByCreatedAtDesc(
            Integer userNo,
            LocalDateTime start,
            LocalDateTime end
    );
}