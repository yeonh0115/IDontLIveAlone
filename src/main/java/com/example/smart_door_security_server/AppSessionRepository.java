package com.example.smart_door_security_server;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AppSessionRepository extends JpaRepository<AppSession, String> { }
