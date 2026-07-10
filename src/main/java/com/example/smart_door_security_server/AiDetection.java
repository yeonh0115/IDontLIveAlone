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
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private SecurityEvent event;

    private String detectedObject;
    private Float confidence;
    private String imagePath;
    @CreationTimestamp
    private LocalDateTime createdAt;
}
