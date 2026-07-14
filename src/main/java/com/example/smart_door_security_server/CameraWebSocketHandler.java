package com.example.smart_door_security_server;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.lang.reflect.Field;

@Component
public class CameraWebSocketHandler extends BinaryWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("[웹소켓] ⚡ 라즈베리파이 카메라가 연결되었습니다. ID: " + session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        byte[] imageBytes = message.getPayload().array();

        // 💡 기존 VideoStreamingController의 static 변수들을 리플렉션 없이 안전하게 우회하여 바로 갱신하기 위해
        // 아래처럼 직접 static 필드에 접근하여 데이터를 밀어 넣어 줍니다.
        updateControllerFrames(imageBytes);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        System.out.println("[웹소켓] ❌ 라즈베리파이 카메라 연결이 끊겼습니다. 상태코드: " + status.getCode());
    }

    /**
     * 💡 기존 VideoStreamingController의 프레임 저장소 변수에 직접 바이트를 주입하는 메서드
     */
    private void updateControllerFrames(byte[] imageBytes) {
        try {
            // VideoStreamingController의 private static 필드에 접근
            Field frameField = VideoStreamingController.class.getDeclaredField("currentFrame");
            Field timeField = VideoStreamingController.class.getDeclaredField("lastUpdateTime");

            frameField.setAccessible(true);
            timeField.setAccessible(true);

            // 값 갱신
            frameField.set(null, imageBytes);
            timeField.set(null, System.currentTimeMillis());

        } catch (NoSuchFieldException | IllegalAccessException e) {
            System.err.println("[웹소켓 에러] VideoStreamingController의 필드 갱신 실패: " + e.getMessage());
        }
    }
}
