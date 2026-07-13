package com.example.smart_door_security_server;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AudioStreamHandler extends BinaryWebSocketHandler {

    private static final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        for (WebSocketSession s : sessions) {
            if (s.isOpen() && !s.getId().equals(session.getId())) {
                try {
                    s.sendMessage(new BinaryMessage(message.getPayload()));
                } catch (IOException e) {
                    System.err.println("Error: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        // 연결 시 세션 ID 출력 로그 추가
        System.out.println("🟢 [WebSocket] 새로운 연결 성공! 세션 ID: " + session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        // 연결 종료 로그 추가
        System.out.println("🔴 [WebSocket] 연결 종료. 세션 ID: " + session.getId() + ", 사유: " + status);
    }
}
