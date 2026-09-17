package com.example.smart_door_security_server;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthControllerTests {
    @Test void reportsDatabaseReadinessWithoutExposingDriverErrors() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        HealthController controller = new HealthController(jdbc);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        assertEquals(200, controller.health().getStatusCode().value());
        assertEquals(Map.of("status", "ok"), controller.health().getBody());
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("sensitive driver details"));
        var failure = controller.health();
        assertEquals(503, failure.getStatusCode().value());
        assertEquals("unavailable", failure.getBody().get("status"));
        assertFalse(failure.getBody().toString().contains("sensitive"));
    }
}
