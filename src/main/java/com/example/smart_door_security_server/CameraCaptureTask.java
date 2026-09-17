package com.example.smart_door_security_server;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Getter @Setter
@Table(name = "camera_capture_tasks", uniqueConstraints = {
    @UniqueConstraint(name="uk_capture_owner_event", columnNames={"user_no", "event_id"})
})
public class CameraCaptureTask {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, EXPIRED }
    @Id @Column(length=36) private String captureTaskId;
    @Column(name="user_no", nullable=false) private Integer userNo;
    @Column(nullable=false, length=36) private String cameraDeviceId;
    @Column(name="event_id", nullable=false, length=100) private String eventId;
    @Column(nullable=false) private LocalDate eventDate;
    @Column(nullable=false) private Instant expiresAt;
    @Column(nullable=false) private Instant createdAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=16) private Status status;
    @Column(nullable=false) private int attempts;
    @Column(length=36) private String leaseToken;
    private Instant leaseUntil;
    @Column(length=500) private String message;
}
