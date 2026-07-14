package com.example.smart_door_security_server;

import jakarta.annotation.PostConstruct; // 추가됨
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.TimeZone; // 추가됨

@SpringBootApplication
public class SmartDoorSecurityServerApplication {

    @PostConstruct
    public void started() {
        // 스프링부트가 구동될 때 JVM 타임존을 서울(KST)로 지정
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(SmartDoorSecurityServerApplication.class, args);
    }
}
