package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.io.File;

@Configuration
@EnableWebSocket // 🎯 웹소켓 기능을 활성화합니다.
@RequiredArgsConstructor // 🎯 AudioStreamHandler 주입을 위해 추가합니다.
public class WebConfig implements WebMvcConfigurer, WebSocketConfigurer { // 🎯 WebSocketConfigurer 인터페이스 추가
    
    // 🎯 제작하신 오디오 웹소켓 핸들러를 주입받습니다.
    private final AudioStreamHandler audioStreamHandler;

    // 1. 기존 정적 리소스(CCTV 이미지 등) 설정
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

    // 🎯 2. 웹소켓 핸들러와 접속 경로(URL)를 매핑하는 핵심 메서드 추가
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(audioStreamHandler, "/ws/audio") // 안드로이드가 ws://IP:포트/ws/audio 로 접속하게 만듭니다.
                .setAllowedOrigins("*"); // 모든 Origin에서의 접속 허용 (CORS 에러 방지)
    }

    // 3. 기존 웹소켓 버퍼 크기 컨테이너 설정
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(512 * 1024); // 바이너리 버퍼 512KB로 확장
        container.setMaxTextMessageBufferSize(512 * 1024);
        return container;
    }
}
