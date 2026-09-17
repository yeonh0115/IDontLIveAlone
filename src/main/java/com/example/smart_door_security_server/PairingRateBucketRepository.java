package com.example.smart_door_security_server;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface PairingRateBucketRepository extends JpaRepository<PairingRateBucket, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PairingRateBucket b where b.bucketKey = :key")
    Optional<PairingRateBucket> findForUpdate(@Param("key") String key);
}
