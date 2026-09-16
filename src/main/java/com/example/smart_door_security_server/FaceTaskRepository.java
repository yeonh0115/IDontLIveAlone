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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from FaceTask t where t.taskId = :taskId")
    Optional<FaceTask> findForUpdate(@Param("taskId") String taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from FaceTask t
            where (t.status = :queued or (t.status = :running and t.leaseExpiresAt <= :now))
              and (:userNo is null or t.userNo = :userNo)
            order by t.createdAt asc, t.taskId asc
            """)
    List<FaceTask> findClaimable(@Param("queued") FaceTaskStatus queued,
                                @Param("running") FaceTaskStatus running,
                                @Param("now") Instant now,
                                @Param("userNo") Integer userNo,
                                Pageable pageable);
}
