package com.example.smart_door_security_server;

import org.springframework.stereotype.Component; // 👈 추가
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

@Component // 👈 필수! 스프링 빈으로 등록해야 WebConfig에서 주입을 받습니다.
public class CameraWebSocketHandler extends BinaryWebSocketHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CameraWebSocketHandler.class);
    private DeviceRegistrationService devices;
    @org.springframework.beans.factory.annotation.Autowired
    void setDevices(DeviceRegistrationService devices) { this.devices = devices; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("[카메라 웹소켓] ⚡ 라즈베리파이 카메라가 연결되었습니다. ID: " + session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        try {
            Integer owner = session == null ? null : (Integer) session.getAttributes().get("cameraOwner");
            if (owner != null) {
                long nextCheck = (long) session.getAttributes().getOrDefault("nextIdentityCheck", 0L);
                if (System.currentTimeMillis() >= nextCheck) {
                    var identity = devices.requireCameraSession((String) session.getAttributes().get("cameraDevice"),
                            owner, (String) session.getAttributes().get("cameraTokenHash"));
                    if (!identity.deviceId().equals(session.getAttributes().get("cameraDevice"))) {
                        session.close(CloseStatus.POLICY_VIOLATION); return;
                    }
                    session.getAttributes().put("nextIdentityCheck", System.currentTimeMillis() + 2000);
                }
            }
            var payload = message.getPayload().asReadOnlyBuffer();
            byte[] imageBytes = new byte[payload.remaining()];
            payload.get(imageBytes);
            VideoStreamingController.updateOwnerFrame(owner, imageBytes);
        } catch (org.springframework.web.server.ResponseStatusException revoked) {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception e) {
            String origin = e.getStackTrace().length == 0 ? "unknown"
                    : e.getStackTrace()[0].getClassName() + "." + e.getStackTrace()[0].getMethodName();
            log.warn("Camera binary frame failed: {} at {}", e.getClass().getSimpleName(), origin);
            if (session != null) session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        System.out.println("[카메라 웹소켓] ❌ 라즈베리파이 카메라 연결이 끊겼습니다. 상태코드: " + status.getCode());
    }
}
