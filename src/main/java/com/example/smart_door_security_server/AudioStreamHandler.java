package com.example.smart_door_security_server;

import org.slf4j.Logger; // 👈 org.slf4j
import org.slf4j.LoggerFactory; // 👈 org.slf4j
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class AudioStreamHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioStreamHandler.class);
    private static final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("🟢 새로운 오디오 스트리밍 세션 연결됨: {}", session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        for (WebSocketSession s : sessions) {
            if (s.isOpen() && !s.getId().equals(session.getId())) {
                try {
                    s.sendMessage(message);
                } catch (IOException e) {
                    log.error("오디오 메세지 중계 오류", e); // 👈 log.error 로 수정
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("🔴 오디오 스트리밍 세션 종료됨: {}", session.getId());
    }
}
