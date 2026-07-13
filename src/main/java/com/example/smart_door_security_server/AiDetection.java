package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_detections")
@Getter
@Setter
@NoArgsConstructor
public class AiDetection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "log_id")
    private IntegratedLog log;

    @Column(name = "detected_object", length = 50)
    private String detectedObject;

    @Column(name = "confidence")
    private Float confidence;

    @Column(name = "image_path", length = 255)
    private String imagePath;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
