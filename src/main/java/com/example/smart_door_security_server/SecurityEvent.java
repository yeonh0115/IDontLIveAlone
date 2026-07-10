package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "security_events")
@Getter
@Setter
@NoArgsConstructor
public class SecurityEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer eventId;

    @Column(nullable = false, length = 50)
    private String eventType;

    @Enumerated(EnumType.STRING)
    private Severity severity = Severity.low;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String deviceSource;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum Severity {
        low, medium, high
    }
}
