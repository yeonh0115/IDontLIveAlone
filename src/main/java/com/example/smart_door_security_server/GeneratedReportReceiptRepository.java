package com.example.smart_door_security_server;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface GeneratedReportReceiptRepository extends JpaRepository<GeneratedReportReceipt, Long> {
    Optional<GeneratedReportReceipt> findByUserNoAndRequestId(Integer userNo, String requestId);
}
