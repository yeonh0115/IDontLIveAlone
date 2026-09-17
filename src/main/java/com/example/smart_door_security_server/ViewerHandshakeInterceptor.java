package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@Component @RequiredArgsConstructor
public class ViewerHandshakeInterceptor implements HandshakeInterceptor {
    private final AppSessionService sessions;
    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String,Object> attributes) {
        try {
            var identity = sessions.requireSession(request.getHeaders().getFirst("Authorization"));
            // Copy identity data while the HTTP request is alive; retain no request or raw token.
            attributes.put("viewerIdentity", identity);
            attributes.put("nextViewerIdentityCheck", System.currentTimeMillis() + 2000);
            return true;
        } catch (ResponseStatusException failure) {
            response.setStatusCode(failure.getStatusCode()); return false;
        }
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) { }
}
