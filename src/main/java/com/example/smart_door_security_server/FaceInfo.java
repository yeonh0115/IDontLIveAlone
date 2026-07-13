package com.example.smart_door_security_server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "face_info")
@Getter
@Setter
@NoArgsConstructor
public class FaceInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "file_path1", nullable = false, length = 255)
    private String filePath1;

    @Column(name = "file_path2", nullable = false, length = 255)
    private String filePath2;

    @Column(name = "file_path3", nullable = false, length = 255)
    private String filePath3;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}