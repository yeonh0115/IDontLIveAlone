package com.example.smart_door_security_server;

import com.example.smart_door_security_server.handler.AudioStreamHandler;
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
        registry.addHandler(audioStreamHandler(), "/audio-stream").setAllowedOrigins("*");
    }

    @Bean
    public AudioStreamHandler audioStreamHandler() {
        return new AudioStreamHandler();
    }
}
