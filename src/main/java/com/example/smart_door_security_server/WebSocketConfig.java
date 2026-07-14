package com.example.smart_door_security_server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 1. 기존 오디오 스트림 핸들러 등록
        registry.addHandler(audioStreamHandler(), "/audio-stream").setAllowedOrigins("*");
        
        // 2. ⚡ 새로 추가된 카메라 고속 스트림 핸들러 등록
        registry.addHandler(cameraWebSocketHandler(), "/ws/camera").setAllowedOrigins("*");
    }

    @Bean
    public AudioStreamHandler audioStreamHandler() {
        return new AudioStreamHandler();
    }

    // ⚡ 카메라 핸들러 빈 추가
    @Bean
    public CameraWebSocketHandler cameraWebSocketHandler() {
        return new CameraWebSocketHandler();
    }
}
