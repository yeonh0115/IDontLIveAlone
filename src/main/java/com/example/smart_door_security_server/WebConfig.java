package com.example.smart_door_security_server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.io.File;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 1. 프로젝트 기준 절대 경로 확보 및 표준화
        String uploadDir = System.getProperty("user.dir") + "/pictures/";
        File file = new File(uploadDir);
        
        // 2. 디렉토리가 없을 시 오동작 방지를 위해 생성 보장
        if (!file.exists()) {
            file.mkdirs();
        }

        // 🎯 [핵심 해결책]: toURI().toString()을 사용하면 OS가 무엇이든(윈도우/리눅스) 
        // 스프링이 요구하는 표준 프로토콜('file:/...' 또는 'file:///') 형식을 완벽하게 자동 생성해 줍니다.
        String resourceLocation = file.toURI().toString();

        registry.addResourceHandler("/pictures/**")
                .addResourceLocations(resourceLocation);

        System.out.println("[WebConfig] 📂 정적 리소스 로딩 디렉토리: " + uploadDir);
        System.out.println("[WebConfig] 🔗 스프링 등록 리소스 위치 프로토콜: " + resourceLocation);
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(512 * 1024); // 바이너리 버퍼 512KB로 확장
        container.setMaxTextMessageBufferSize(512 * 1024);
        return container;
    }
}
