package com.example.smart_door_security_server;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface EventPhotoRepository extends JpaRepository<EventPhoto, Long> {
    Optional<EventPhoto> findByUserNoAndSourceEventId(Integer userNo, String sourceEventId);
}
