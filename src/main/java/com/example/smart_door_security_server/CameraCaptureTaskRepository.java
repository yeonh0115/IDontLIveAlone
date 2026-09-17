package com.example.smart_door_security_server;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;
public interface CameraCaptureTaskRepository extends JpaRepository<CameraCaptureTask, String> {
    Optional<CameraCaptureTask> findByUserNoAndEventId(Integer userNo, String eventId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CameraCaptureTask c where c.captureTaskId=:id")
    Optional<CameraCaptureTask> findForUpdate(@Param("id") String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CameraCaptureTask c where c.cameraDeviceId=:deviceId and c.userNo=:userNo "
        + "and (c.status=:queued or (c.status=:running and (c.leaseUntil<=:now or c.expiresAt<=:now))) order by c.createdAt")
    List<CameraCaptureTask> findClaimable(@Param("deviceId") String deviceId, @Param("userNo") Integer userNo,
        @Param("queued") CameraCaptureTask.Status queued, @Param("running") CameraCaptureTask.Status running,
        @Param("now") Instant now, Pageable pageable);
}
