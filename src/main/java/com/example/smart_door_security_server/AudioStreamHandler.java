package com.example.backend.handler;

import org.slf.Logger;
import org.slf.LoggerFactory;
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

    @Override;
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        // 들어온 Audio PCM 버퍼 패킷을 연결된 모든 클라이언트(라즈베리파이 등)로 실시간 중계
        for (WebSocketSession s : sessions) {
            if (s.isOpen() && !s.getId().equals(session.getId())) {
                try {
                    s.sendMessage(message);
                } catch (IOException e) {
                    log.e("오디오 메세지 중계 오류", e);
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
