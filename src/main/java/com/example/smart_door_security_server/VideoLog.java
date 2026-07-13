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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "log_id")
    private IntegratedLog log;

    @Column(name = "video_path", length = 255)
    private String videoPath;

    @Column(name = "thumbnail_path", length = 255)
    private String thumbnailPath;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
