package com.example.smart_door_security_server;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // "./pictures/" 폴더에 저장된 이미지를 외부에서 URL로 접근할 수 있게 허용
        registry.addResourceHandler("/pictures/**")
                .addResourceLocations("file:./pictures/");
    }
}
