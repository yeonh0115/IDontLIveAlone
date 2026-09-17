package com.example.smart_door_security_server;

import com.example.smart_door_security_server.IntegratedLog;
import com.example.smart_door_security_server.IntegratedLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.format.annotation.DateTimeFormat;
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
    private final WriterAccessControl writerAccessControl;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<IntegratedLog>> getIntegratedLogs(@RequestParam(value="userNo", required=false) Integer userNo,
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        // Keep the existing current-day route; historical/date-specific reads require the report writer's token.
        if (date != null) {
            userNo = writerAccessControl.resolveReport(authorization, userNo).userNo();
            if (!userRepository.existsById(userNo)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "설정된 사용자를 찾을 수 없습니다.");
            }
        }
        if (userNo == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "userNo가 필요합니다.");
        LocalDate today = date == null ? LocalDate.now(java.time.ZoneId.of("Asia/Seoul")) : date;


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
