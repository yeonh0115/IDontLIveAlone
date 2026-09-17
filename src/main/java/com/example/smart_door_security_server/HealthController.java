package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {
    private final JdbcTemplate jdbc;

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, String>> health() {
        try {
            if (Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class))) {
                return ResponseEntity.ok(Map.of("status", "ok"));
            }
        } catch (DataAccessException ignored) {
            // Do not expose connection settings, driver exceptions, or account data.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "unavailable", "message", "데이터베이스 연결을 확인할 수 없습니다."));
    }
}
