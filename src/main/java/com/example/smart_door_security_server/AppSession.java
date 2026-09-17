package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "app_sessions")
@Getter @Setter
public class AppSession {
    @Id @Column(length = 64) private String tokenHash;
    @Column(nullable = false) private Integer userNo;
    @Column(nullable = false) private Instant expiresAt;
}
