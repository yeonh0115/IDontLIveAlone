package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;

@Entity
@Table(name = "device_event_receipts", uniqueConstraints =
        @UniqueConstraint(columnNames = {"user_no", "source_event_id"}))
@Getter @Setter @NoArgsConstructor
public class DeviceEventReceipt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_no", nullable = false)
    private Integer userNo;
    @Column(name = "source_event_id", nullable = false, length = 100)
    private String sourceEventId;
    @Column(name = "log_id", nullable = false)
    private Integer logId;
    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;
}
