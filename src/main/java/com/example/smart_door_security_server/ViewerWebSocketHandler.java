package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

@Component @RequiredArgsConstructor
public class ViewerWebSocketHandler extends TextWebSocketHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ViewerWebSocketHandler.class);
    private final AppSessionService sessions;

    @Override protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        if (!"next".equals(message.getPayload())) {
            socket.close(CloseStatus.NOT_ACCEPTABLE); return;
        }
        try {
            var identity = (AppSessionService.SessionIdentity) socket.getAttributes().get("viewerIdentity");
            // The expiry check runs for every requested frame, independent of the DB check interval.
            if (identity == null || !identity.expiresAt().isAfter(Instant.now())) {
                socket.close(CloseStatus.POLICY_VIOLATION); return;
            }
            long now = System.currentTimeMillis();
            long nextCheck = (long) socket.getAttributes().getOrDefault("nextViewerIdentityCheck", 0L);
            if (now >= nextCheck) {
                identity = sessions.requireSessionHash(identity.tokenHash(), identity.userNo());
                socket.getAttributes().put("viewerIdentity", identity);
                socket.getAttributes().put("nextViewerIdentityCheck", now + 2000);
            }
            if (!identity.expiresAt().isAfter(Instant.now())) {
                socket.close(CloseStatus.POLICY_VIOLATION); return;
            }
            now = System.currentTimeMillis();
            var frame = VideoStreamingController.latestOwnerFrame(identity.userNo());
            if (frame == null || now - frame.updatedAt() > 1000
                    || frame == socket.getAttributes().get("lastViewerFrame")) {
                socket.sendMessage(new TextMessage("waiting")); return;
            }
            if (frame.jpeg().length > 512 * 1024) {
                socket.close(CloseStatus.TOO_BIG_TO_PROCESS); return;
            }
            socket.sendMessage(new BinaryMessage(frame.jpeg()));
            socket.getAttributes().put("lastViewerFrame", frame);
        } catch (ResponseStatusException revoked) {
            socket.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception failure) {
            log.warn("Viewer frame request failed: {}", failure.getClass().getSimpleName());
            socket.close(CloseStatus.SERVER_ERROR);
        }
    }
}
