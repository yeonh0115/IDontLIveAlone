package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;

@Entity
@Table(name = "event_photos", uniqueConstraints =
        @UniqueConstraint(columnNames = {"user_no", "source_event_id"}))
@Getter
@Setter
@NoArgsConstructor
public class EventPhoto {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_no", nullable = false)
    private Integer userNo;
    @Column(name = "source_event_id", nullable = false, length = 100)
    private String sourceEventId;
    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;
    @Column(name = "photo_url", nullable = false, length = 1000)
    private String photoUrl;
}
