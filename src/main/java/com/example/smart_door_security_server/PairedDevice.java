package com.example.smart_door_security_server;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "paired_devices", uniqueConstraints = {
    @UniqueConstraint(name = "uk_device_owner_role", columnNames = {"user_no", "role"})
})
@Getter @Setter
public class PairedDevice {
    @Id @Column(length = 36) private String deviceId;
    @Column(nullable = false, unique = true, length = 64) private String tokenHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private DeviceRole role;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "user_no") private Integer userNo;
    @Column(unique = true, length = 8) private String pairingCode;
    private Instant pairingExpiresAt;
    @Column(nullable = false) private Instant createdAt;
}
