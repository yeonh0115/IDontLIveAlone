package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "system_status")
@Getter
@Setter
@NoArgsConstructor
public class SystemStatus {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Enumerated(EnumType.STRING)
    private DoorStatus doorStatus = DoorStatus.closed;

    private LocalDateTime lastActivity;
    private LocalDateTime lastSyncTime;

    public enum DoorStatus {
        open, closed
    }
}
