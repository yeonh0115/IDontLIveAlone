package com.example.smart_door_security_server;

import com.example.smart_door_security_server.FaceInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface FaceInfoRepository extends JpaRepository<FaceInfo, Long> {
    Optional<FaceInfo> findByUserId(String userId);
}
