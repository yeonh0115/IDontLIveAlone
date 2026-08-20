package com.example.smart_door_security_server;

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

    // 같은 패키지 안의 @Component 빈을 자동으로 주입받습니다.
    private final AudioStreamHandler audioStreamHandler;
    private final CameraWebSocketHandler cameraWebSocketHandler;

    // 1. 정적 리소스 설정 (/pictures/** 및 /uploads/** 경로 둘 다 매핑)
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String baseDir = System.getProperty("user.dir");
        
        // pictures 및 uploads 디렉토리 생성 보장
        File picturesDir = new File(baseDir + "/pictures/");
        File uploadsDir = new File(baseDir + "/uploads/");
        
        if (!picturesDir.exists()) picturesDir.mkdirs();
        if (!uploadsDir.exists()) uploadsDir.mkdirs();

        String picturesLocation = picturesDir.toURI().toString();
        String uploadsLocation = uploadsDir.toURI().toString();

        // /pictures/** 요청 처리
        registry.addResourceHandler("/pictures/**")
                .addResourceLocations(picturesLocation);

        // /uploads/** 요청 처리 (404 에러 방지)
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadsLocation, picturesLocation);

        System.out.println("[WebConfig] 📂 정적 리소스 로딩 디렉토리: " + baseDir);
        System.out.println("[WebConfig] 🔗 /pictures/ 및 /uploads/ 매핑 완료");
    }

    // 2. 비동기 요청 타임아웃 설정
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(-1);
        System.out.println("[WebConfig] ⚙️ Async Request Timeout 설정을 무제한(-1)으로 완료했습니다.");
    }

    // 3. 웹소켓 핸들러 등록
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 오디오 웹소켓
        registry.addHandler(audioStreamHandler, "/ws/audio", "/audio-stream")
                .setAllowedOrigins("*");

        // 카메라 웹소켓
        registry.addHandler(cameraWebSocketHandler, "/ws/camera")
                .setAllowedOrigins("*");
    }

    // 4. 웹소켓 컨테이너 설정
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
