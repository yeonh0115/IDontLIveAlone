package com.example.smart_door_security_server;

import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class TaskQueueService {
    private final Queue<Map<String, Object>> queue = new ConcurrentLinkedQueue<>();

    public void addTrainTask(String userId, List<String> imageUrls) {
        Map<String, Object> task = new HashMap<>();
        task.put("task_id", UUID.randomUUID().toString());
        task.put("type", "TRAIN");
        task.put("user_id", userId);
        task.put("image_urls", imageUrls); // 파이썬 코드에서 기대하는 key
        queue.offer(task);
    }

    public Map<String, Object> getNextTask() {
        return queue.poll(); // 가장 오래된 작업을 꺼냄 (없으면 null 반환)
    }
}
