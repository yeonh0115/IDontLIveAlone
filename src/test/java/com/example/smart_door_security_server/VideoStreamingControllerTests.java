package com.example.smart_door_security_server;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class VideoStreamingControllerTests {
    @Test void websocketUsesOnlyRemainingBytesAndFrameCannotChangeHalfwayThroughResponse() throws Exception {
        ByteBuffer payload = ByteBuffer.wrap(new byte[]{99, 1, 2, 3, 99});
        payload.position(1);
        payload.limit(4);
        new CameraWebSocketHandler().handleBinaryMessage(null, new BinaryMessage(payload));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        OutputStream oneFrame = new OutputStream() {
            boolean headerWritten;
            @Override public void write(int value) { captured.write(value); }
            @Override public void write(byte[] value) throws IOException {
                captured.write(value);
                if (!headerWritten) {
                    headerWritten = true;
                    VideoStreamingController.updateFrameDirectly(new byte[]{4, 5, 6, 7, 8});
                }
            }
            @Override public void flush() throws IOException { throw new IOException("test client disconnected"); }
        };
        new VideoStreamingController().getVideoFeed().getBody().writeTo(oneFrame);
        byte[] response = captured.toByteArray();
        String header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 3\r\n\r\n";
        assertTrue(new String(response, StandardCharsets.ISO_8859_1).startsWith(header));
        assertArrayEquals(new byte[]{1, 2, 3}, java.util.Arrays.copyOfRange(response, header.length(), header.length() + 3));
    }
}
