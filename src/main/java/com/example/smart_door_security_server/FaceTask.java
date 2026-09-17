package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "face_tasks", indexes = {
        @Index(name = "idx_face_tasks_claim", columnList = "status,lease_expires_at,created_at"),
        @Index(name = "idx_face_tasks_owner", columnList = "user_no,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class FaceTask {
    @Id
    @Column(name = "task_id", length = 36)
    private String taskId;
    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;
    @Column(name = "user_no", nullable = false)
    private Integer userNo;
    @Column(name = "image_url1", nullable = false, length = 512)
    private String imageUrl1;
    @Column(name = "image_url2", nullable = false, length = 512)
    private String imageUrl2;
    @Column(name = "image_url3", nullable = false, length = 512)
    private String imageUrl3;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FaceTaskStatus status;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "device_id", length = 100)
    private String deviceId;
    @Column(name = "lease_token", length = 36)
    private String leaseToken;
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;
    @Column(name = "result_status", length = 16)
    private String resultStatus;
    @Column(length = 1000)
    private String message;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(nullable=false, columnDefinition="boolean default false")
    private boolean temporaryImagesDeleted;

    public List<String> imageUrls() {
        return List.of(imageUrl1, imageUrl2, imageUrl3);
    }
}
