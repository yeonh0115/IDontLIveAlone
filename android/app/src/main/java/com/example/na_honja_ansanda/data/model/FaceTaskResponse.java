package com.example.na_honja_ansanda.data.model;

/** The server's training task, separate from accepting the uploaded photos. */
public class FaceTaskResponse {
    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, UNKNOWN }

    private String taskId;
    private String status;
    private String message;

    public String getTaskId() { return taskId; }

    public State getState() {
        if (status == null) return State.UNKNOWN;
        try {
            return State.valueOf(status);
        } catch (IllegalArgumentException exception) {
            return State.UNKNOWN;
        }
    }

    public boolean hasTaskId() { return taskId != null && !taskId.trim().isEmpty(); }
    public boolean isComplete() { return getState() == State.SUCCEEDED; }
    public boolean isPending() { return getState() == State.QUEUED || getState() == State.RUNNING; }
}
