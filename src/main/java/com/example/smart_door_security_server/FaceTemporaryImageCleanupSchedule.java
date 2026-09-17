package com.example.smart_door_security_server;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="FACE_TEMP_CLEANUP_SCHEDULED", havingValue="true", matchIfMissing=true)
public class FaceTemporaryImageCleanupSchedule {
    private final FaceTemporaryImageCleanup cleanup;
    @Scheduled(fixedDelay=60000, initialDelay=60000)
    public void run() { cleanup.cleanup(); }
}
