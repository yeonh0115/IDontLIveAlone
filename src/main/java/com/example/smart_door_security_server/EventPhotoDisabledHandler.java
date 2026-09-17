package com.example.smart_door_security_server;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestControllerAdvice
public class EventPhotoDisabledHandler {
    @ExceptionHandler(EventPhotoSettings.Disabled.class)
    public ResponseEntity<Map<String,Object>> disabled() {
        return ResponseEntity.status(410).body(Map.of("success", false, "status", "disabled",
                "message", "이벤트 사진 저장 기능이 꺼져 있습니다."));
    }
}
