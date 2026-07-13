package com.example.smart_door_security_server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_reports", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_no", "report_date"})
})
@Getter
@Setter
@NoArgsConstructor
public class DailyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_no", nullable = false)
    private User user;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "total_events")
    private Integer totalEvents = 0;

    @Column(name = "high_risk_events")
    private Integer highRiskEvents = 0;

    @Column(name = "report_text", columnDefinition = "TEXT")
    private String reportText;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}