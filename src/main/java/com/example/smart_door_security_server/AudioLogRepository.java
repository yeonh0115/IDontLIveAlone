package com.example.smart_door_security_server.repository;

import com.example.smart_door_security_server.domain.AudioLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AudioLogRepository extends JpaRepository<AudioLog, Integer> {
}