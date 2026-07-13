package com.example.smart_door_security_server;
import com.example.smart_door_security_server.domain.AudioLog;
import com.example.smart_door_security_server.domain.IntegratedLog;
import com.example.smart_door_security_server.repository.AudioLogRepository;
import com.example.smart_door_security_server.repository.IntegratedLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audio")
@RequiredArgsConstructor
public class AudioController {

    private final AudioLogRepository audioLogRepository;
    private final IntegratedLogRepository integratedLogRepository;

    @PostMapping("/log")
    public ResponseEntity<String> saveAudioLog(@RequestParam String path, @RequestParam Integer logId) {
        try {
            IntegratedLog integratedLog = integratedLogRepository.findById(logId).orElse(null);
            AudioLog log = new AudioLog();
            log.setAudioPath(path);
            log.setLog(integratedLog);

            audioLogRepository.save(log);
            return ResponseEntity.ok("DB 저장 성공");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("DB 저장 실패: " + e.getMessage());
        }
    }
}
