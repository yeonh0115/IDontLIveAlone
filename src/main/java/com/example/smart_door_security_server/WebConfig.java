package com.example.smart_door_security_server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 🎯 [해결] 파일 저장용 절대 경로 주소와 완벽하게 일치시킵니다.
        String uploadDir = System.getProperty("user.dir") + "/pictures/";
        
        // OS별 호환성을 보장하기 위해 슬래시(/) 형태로 규격화합니다.
        uploadDir = uploadDir.replace("\\", "/");
        if (!uploadDir.endsWith("/")) {
            uploadDir += "/";
        }

        registry.addResourceHandler("/pictures/**")
                .addResourceLocations("file:" + uploadDir);

        System.out.println("[WebConfig] 📂 정적 리소스 실제 매핑 경로: " + uploadDir);
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(512 * 1024); // 바이너리 버퍼 512KB로 확장
        container.setMaxTextMessageBufferSize(512 * 1024);
        return container;
    }
}
