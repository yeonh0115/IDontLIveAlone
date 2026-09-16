package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TaskController {

    private final TaskQueueService taskQueueService;

    @GetMapping("/get-task")
    public ResponseEntity<?> getTask(@RequestParam("device_id") String deviceId,
                                     @RequestParam(value = "user_no", required = false) Integer userNo) {
        Map<String, Object> task = taskQueueService.getNextTask(deviceId, userNo);
        return task == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(task);
    }

    @GetMapping("/face/tasks/{taskId}")
    public FaceTaskResponse getStatus(@PathVariable String taskId, @RequestParam String userId) {
        return taskQueueService.getStatus(taskId, userId);
    }

    @PostMapping("/face/tasks/{taskId}/lease")
    public Map<String, String> renewLease(@PathVariable String taskId, @RequestBody Map<String, Object> body) {
        taskQueueService.renewLease(taskId, string(body, "device_id"), string(body, "lease_token"));
        return Map.of("status", "success");
    }

    @PostMapping("/result")
    public Map<String, String> receiveResult(@RequestBody Map<String, Object> body) {
        if (!(body.get("result") instanceof Map<?, ?> result)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "result 객체가 필요합니다.");
        }
        taskQueueService.recordResult(string(body, "task_id"), string(body, "type"),
                string(body, "device_id"), string(body, "lease_token"),
                string(result, "status"), string(result, "message"));
        return Map.of("status", "success");
    }

    private static String string(Map<?, ?> body, String key) {
        Object value = body.get(key);
        if (value != null && !(value instanceof String)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "는 문자열이어야 합니다.");
        }
        return (String) value;
    }
}
