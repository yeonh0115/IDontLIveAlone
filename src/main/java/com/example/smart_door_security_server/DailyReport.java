package com.example.smart_door_security_server;
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

    // 📌 [추가] 이미지 URL을 저장할 컬럼 생성 (안드로이드의 photoUrl과 자동 매핑)
    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
