package com.example.smart_door_security_server;

public record FaceTaskResponse(String taskId, FaceTaskStatus status, String message) {
    public static FaceTaskResponse from(FaceTask task) {
        return new FaceTaskResponse(task.getTaskId(), task.getStatus(), task.getMessage());
    }
}
