package com.example.smart_door_security_server;

import org.springframework.stereotype.Component; // 👈 추가
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

@Component // 👈 필수! 스프링 빈으로 등록해야 WebConfig에서 주입을 받습니다.
public class CameraWebSocketHandler extends BinaryWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("[카메라 웹소켓] ⚡ 라즈베리파이 카메라가 연결되었습니다. ID: " + session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        try {
            byte[] imageBytes = message.getPayload().array();
            VideoStreamingController.updateFrameDirectly(imageBytes);
        } catch (Exception e) {
            System.err.println("[카메라 웹소켓 에러] 데이터 처리 중 예외 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        System.out.println("[카메라 웹소켓] ❌ 라즈베리파이 카메라 연결이 끊겼습니다. 상태코드: " + status.getCode());
    }
}
