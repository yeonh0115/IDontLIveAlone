package com.example.smart_door_security_server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.TimeZone;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class SmartDoorSecurityServerApplication {

    public static void main(String[] args) {
        // Set the process zone before Spring initializes JDBC/Hibernate. Changing it in
        // @PostConstruct made cached JDBC calendars disagree and shifted SQL DATE values.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(SmartDoorSecurityServerApplication.class, args);
    }
}
