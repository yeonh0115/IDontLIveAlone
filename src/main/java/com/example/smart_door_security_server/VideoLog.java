package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "video_logs")
@Getter
@Setter
@NoArgsConstructor
public class VideoLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private SecurityEvent event;

    private String videoPath;
    private String thumbnailPath;
    @CreationTimestamp
    private LocalDateTime createdAt;
}
