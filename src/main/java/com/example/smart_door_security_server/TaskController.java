package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TaskController {

    private final TaskQueueService taskQueueService;

    // 파이썬 코드가 1초마다 찌르는 엔드포인트
    @GetMapping("/get-task")
    public ResponseEntity<?> getTask() {
        Map<String, Object> task = taskQueueService.getNextTask();
        if (task != null) {
            return ResponseEntity.ok(task); // 일거리가 있으면 200 OK와 함께 전달
        } else {
            return ResponseEntity.noContent().build(); // 파이썬의 '204 No Content' 처리 로직과 완벽히 호환
        }
    }

    // 파이썬 코드가 학습/인식을 마치고 결과를 보고하는 엔드포인트
    @PostMapping("/result")
    public ResponseEntity<?> receiveResult(@RequestBody Map<String, Object> result) {
        System.out.println("[라즈베리파이 응답 수신]: " + result);
        return ResponseEntity.ok(Map.of("status", "success"));
    }
}
