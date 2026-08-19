package com.example.smart_door_security_server;

// 💡 핸들러 패키지 import 추가
import com.example.smart_door_security_server.handler.AudioStreamHandler;
import com.example.smart_door_security_server.handler.CameraWebSocketHandler; // CameraWebSocketHandler 위치 확인 필요

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.io.File;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer, WebSocketConfigurer { 

    private final AudioStreamHandler audioStreamHandler;
    private final CameraWebSocketHandler cameraWebSocketHandler; // 🎯 카메라 핸들러 추가 주입

    // 1. 정적 리소스 설정
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadDir = System.getProperty("user.dir") + "/pictures/";
        File file = new File(uploadDir);
        
        if (!file.exists()) {
            file.mkdirs();
        }

        String resourceLocation = file.toURI().toString();

        registry.addResourceHandler("/pictures/**")
                .addResourceLocations(resourceLocation);

        System.out.println("[WebConfig] 📂 정적 리소스 로딩 디렉토리: " + uploadDir);
        System.out.println("[WebConfig] 🔗 스프링 등록 리소스 위치 프로토콜: " + resourceLocation);
    }

    // 2. 비동기 요청 타임아웃 설정
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(-1); 
        System.out.println("[WebConfig] ⚙️ Async Request Timeout 설정을 무제한(-1)으로 완료했습니다.");
    }

    // 3. 웹소켓 핸들러 통합 등록 (오디오 + 카메라)
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 오디오 스트림 핸들러 등록
        registry.addHandler(audioStreamHandler, "/ws/audio", "/audio-stream")
                .setAllowedOrigins("*");

        // 카메라 고속 스트림 핸들러 등록
        registry.addHandler(cameraWebSocketHandler, "/ws/camera")
                .setAllowedOrigins("*");
    }

    // 4. 웹소켓 버퍼 및 타임아웃 설정
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        container.setMaxTextMessageBufferSize(512 * 1024);
        container.setMaxSessionIdleTimeout(10 * 60 * 1000L); 
        
        System.out.println("[WebConfig] ⚙️ WebSocket Max Session Idle Timeout 설정을 10분으로 완료했습니다.");
        return container;
    }
}
