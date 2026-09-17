package com.example.smart_door_security_server;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "pairing_rate_buckets") @Getter @Setter
public class PairingRateBucket {
    @Id @Column(length = 64) private String bucketKey;
    @Column(nullable = false) private Instant windowStart;
    @Column(nullable = false) private int attempts;
}
