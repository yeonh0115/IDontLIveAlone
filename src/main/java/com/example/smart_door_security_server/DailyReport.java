package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_reports")
@Getter @Setter @NoArgsConstructor
public class DailyReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private LocalDate reportDate;
    private Integer totalEvents = 0;
    private Integer highRiskEvents = 0;
    @Column(columnDefinition = "TEXT")
    private String reportText;
    @CreationTimestamp
    private LocalDateTime createdAt;
}
