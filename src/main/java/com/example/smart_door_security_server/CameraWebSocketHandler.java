package com.example.smart_door_security_server;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.lang.reflect.Field;

public class CameraWebSocketHandler extends BinaryWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("[카메라 웹소켓] ⚡ 라즈베리파이 카메라가 연결되었습니다. ID: " + session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        byte[] imageBytes = message.getPayload().array();
        updateControllerFrames(imageBytes);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        System.out.println("[카메라 웹소켓] ❌ 라즈베리파이 카메라 연결이 끊겼습니다. 상태코드: " + status.getCode());
    }

    /**
     * 💡 기존 VideoStreamingController의 static 변수에 직접 프레임 데이터를 주입
     */
    private void updateControllerFrames(byte[] imageBytes) {
        try {
            Field frameField = VideoStreamingController.class.getDeclaredField("currentFrame");
            Field timeField = VideoStreamingController.class.getDeclaredField("lastUpdateTime");

            frameField.setAccessible(true);
            timeField.setAccessible(true);

            frameField.set(null, imageBytes);
            timeField.set(null, System.currentTimeMillis());

        } catch (NoSuchFieldException | IllegalAccessException e) {
            System.err.println("[카메라 웹소켓 에러] VideoStreamingController 필드 갱신 실패: " + e.getMessage());
        }
    }
}
