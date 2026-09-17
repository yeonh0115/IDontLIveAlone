package com.example.smart_door_security_server;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FaceTaskRepository extends JpaRepository<FaceTask, String> {
    @Query("select t.taskId from FaceTask t where t.temporaryImagesDeleted=false "
            + "and (t.status=com.example.smart_door_security_server.FaceTaskStatus.SUCCEEDED "
            + "or t.status=com.example.smart_door_security_server.FaceTaskStatus.FAILED or t.createdAt<=:cutoff) order by t.createdAt")
    List<String> findCleanupCandidates(@Param("cutoff") Instant cutoff, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from FaceTask t where t.taskId = :taskId")
    Optional<FaceTask> findForUpdate(@Param("taskId") String taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from FaceTask t
            where (t.status = :queued or (t.status = :running and t.leaseExpiresAt <= :now))
              and (:userNo is null or t.userNo = :userNo)
              and (:paired = true or not exists (select d.deviceId from PairedDevice d
                   where d.userNo=t.userNo and d.role=com.example.smart_door_security_server.DeviceRole.CAMERA))
            order by t.createdAt asc, t.taskId asc
            """)
    List<FaceTask> findClaimable(@Param("queued") FaceTaskStatus queued,
                                @Param("running") FaceTaskStatus running,
                                @Param("now") Instant now,
                                @Param("userNo") Integer userNo,
                                @Param("paired") boolean paired,
                                Pageable pageable);
}
