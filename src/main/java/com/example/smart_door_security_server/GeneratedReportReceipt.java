package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;

@Entity
@Table(name = "generated_report_receipts", uniqueConstraints =
        @UniqueConstraint(columnNames = {"user_no", "request_id"}))
@Getter @Setter @NoArgsConstructor
public class GeneratedReportReceipt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_no", nullable = false)
    private Integer userNo;
    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;
    @Column(name = "report_id", nullable = false)
    private Integer reportId;
    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;
}
