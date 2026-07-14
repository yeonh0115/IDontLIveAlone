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
        // "./pictures/" 폴더에 저장된 이미지를 외부에서 URL로 접근할 수 있게 허용
        registry.addResourceHandler("/pictures/**")
                .addResourceLocations("file:./pictures/");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(512 * 1024); # 바이너리 버퍼 512KB로 확장
        container.setMaxTextMessageBufferSize(512 * 1024);
        return container;
    }
}
