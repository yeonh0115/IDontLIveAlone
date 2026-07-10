package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "environment_logs")
@Getter
@Setter
@NoArgsConstructor
public class EnvironmentLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Float temperature;
    private Float humidity;
    @CreationTimestamp
    private LocalDateTime createdAt;
}