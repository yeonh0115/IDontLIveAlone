package com.example.smart_door_security_server;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.time.Duration;

@Component
public class FaceTemporaryImageCleanup {
    private final FaceTaskRepository tasks;
    private final FaceImageStorage storage;
    private final TransactionTemplate transaction;
    public FaceTemporaryImageCleanup(FaceTaskRepository tasks, FaceImageStorage storage, PlatformTransactionManager manager) {
        this.tasks=tasks; this.storage=storage; transaction=new TransactionTemplate(manager);
    }
    // Also retries cleanup after an interrupted process or failed filesystem deletion.
    public void cleanup() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        for (String id : tasks.findCleanupCandidates(cutoff, PageRequest.of(0, 100))) {
            Boolean ready = transaction.execute(status -> {
                FaceTask task = tasks.findForUpdate(id).orElse(null);
                if (task == null) return false;
                if (task.getStatus() == FaceTaskStatus.QUEUED || task.getStatus() == FaceTaskStatus.RUNNING) {
                    if (task.getCreatedAt().isAfter(cutoff)) return false;
                    task.setStatus(FaceTaskStatus.FAILED); task.setFinishedAt(Instant.now()); task.setUpdatedAt(Instant.now());
                    task.setMessage("24시간이 지나 임시 얼굴 사진이 삭제되었습니다. 다시 등록해 주세요.");
                }
                return true;
            });
            if (Boolean.TRUE.equals(ready) && storage.deleteTask(id)) {
                transaction.executeWithoutResult(status -> tasks.findForUpdate(id).ifPresent(task -> task.setTemporaryImagesDeleted(true)));
            }
        }
    }
}
