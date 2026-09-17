package com.example.smart_door_security_server;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface PairedDeviceRepository extends JpaRepository<PairedDevice, String> {
    Optional<PairedDevice> findByTokenHash(String tokenHash);
    Optional<PairedDevice> findByPairingCode(String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PairedDevice d where d.pairingCode = :code")
    Optional<PairedDevice> findCodeForUpdate(@Param("code") String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PairedDevice d where d.tokenHash = :hash")
    Optional<PairedDevice> findTokenForUpdate(@Param("hash") String hash);
    Optional<PairedDevice> findByUserNoAndRole(Integer userNo, DeviceRole role);
    List<PairedDevice> findByUserNoOrderByRole(Integer userNo);
    boolean existsByPairingCode(String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PairedDevice d where d.deviceId = :id")
    Optional<PairedDevice> findForUpdate(@Param("id") String id);
}
