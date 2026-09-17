package com.example.smart_door_security_server;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@Component @RequiredArgsConstructor
public class CameraHandshakeInterceptor implements HandshakeInterceptor {
    private final DeviceRegistrationService devices;
    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String,Object> attributes) {
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization == null) {
            negotiateAck(request, response, attributes);
            return true; // Legacy frames have a separate, anonymous channel.
        }
        try {
            var camera = devices.require(authorization, DeviceRole.CAMERA, null);
            attributes.put("cameraOwner", camera.userNo()); attributes.put("cameraDevice", camera.deviceId());
            // Servlet request headers are recycled after the WebSocket upgrade. Keep only
            // a credential hash for subsequent revocation checks, never the bearer secret.
            attributes.put("cameraTokenHash", TokenSecrets.hash(TokenSecrets.bearer(authorization)));
            negotiateAck(request, response, attributes);
            return true;
        } catch (ResponseStatusException failure) {
            response.setStatusCode(failure.getStatusCode()); return false;
        }
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) { }

    private void negotiateAck(ServerHttpRequest request, ServerHttpResponse response, Map<String,Object> attributes) {
        if ("1".equals(request.getHeaders().getFirst("X-Camera-Ack"))) {
            attributes.put("cameraAck", true);
            response.getHeaders().set("X-Camera-Ack", "1");
        }
    }
}
