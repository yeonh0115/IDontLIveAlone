package com.example.smart_door_security_server;
import com.example.smart_door_security_server.AudioLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AudioLogRepository extends JpaRepository<AudioLog, Integer> {
}
