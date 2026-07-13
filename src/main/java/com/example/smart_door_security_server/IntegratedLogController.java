package com.example.smart_door_security_server;

import com.example.smart_door_security_server.domain.IntegratedLog;
import com.example.smart_door_security_server.repository.IntegratedLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/logs")
public class IntegratedLogController {

    private final IntegratedLogRepository integratedLogRepository;

    @GetMapping
    public ResponseEntity<List<IntegratedLog>> getIntegratedLogs(@RequestParam("userNo") Integer userNo) {

        LocalDate today = LocalDate.now();


        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(LocalTime.MAX);


        List<IntegratedLog> logs = integratedLogRepository.findByUserNoAndCreatedAtBetweenOrderByCreatedAtDesc(
                userNo,
                startOfDay,
                endOfDay
        );

        return ResponseEntity.ok(logs);
    }
}
