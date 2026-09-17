package com.example.smart_door_security_server;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoStreamingControllerTests {
    @Test void pairedFramesAreOnlyVisibleToTheirAccountAndNeverLeakIntoLegacyFeed() throws Exception {
        AppSessionService sessions=mock(AppSessionService.class);
        when(sessions.requireUser("owner-one")).thenReturn(111);
        when(sessions.requireUser("owner-two")).thenReturn(222);
        VideoStreamingController controller=new VideoStreamingController();
        controller.setAccess(sessions,mock(DeviceRegistrationService.class));
        VideoStreamingController.updateFrameDirectly(new byte[]{9});
        VideoStreamingController.updateOwnerFrame(111,new byte[]{1,2});
        VideoStreamingController.updateOwnerFrame(222,new byte[]{3,4,5});
        assertArrayEquals(new byte[]{1,2}, readOneFrame(controller,"owner-one"));
        assertArrayEquals(new byte[]{3,4,5}, readOneFrame(controller,"owner-two"));
        assertArrayEquals(new byte[]{9}, readOneFrame(controller,null));
        VideoStreamingController.clearOwnerFrame(111); VideoStreamingController.clearOwnerFrame(222);
    }

    @Test void cameraHandshakeRejectsWrongRoleAndExpiredDeviceBeforeOpeningPairedChannel() throws Exception {
        DeviceRegistrationService devices=mock(DeviceRegistrationService.class);
        var request=mock(org.springframework.http.server.ServerHttpRequest.class);
        var response=mock(org.springframework.http.server.ServerHttpResponse.class);
        var headers=new org.springframework.http.HttpHeaders(); headers.setBearerAuth("test-device-token-with-at-least-32-characters");
        when(request.getHeaders()).thenReturn(headers);
        when(devices.require("Bearer test-device-token-with-at-least-32-characters",DeviceRole.CAMERA,null))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        var interceptor=new CameraHandshakeInterceptor(devices);
        assertFalse(interceptor.beforeHandshake(request,response,new CameraWebSocketHandler(),new java.util.HashMap<>()));
        verify(response).setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
        doReturn(new DeviceRegistrationService.DeviceIdentity("camera",DeviceRole.CAMERA,111))
                .when(devices).require("Bearer test-device-token-with-at-least-32-characters",DeviceRole.CAMERA,null);
        var attributes=new java.util.HashMap<String,Object>();
        assertTrue(interceptor.beforeHandshake(request,response,new CameraWebSocketHandler(),attributes));
        assertEquals(111,attributes.get("cameraOwner")); assertEquals("camera",attributes.get("cameraDevice"));
        assertFalse(attributes.values().contains("Bearer test-device-token-with-at-least-32-characters"));
        assertEquals(TokenSecrets.hash("test-device-token-with-at-least-32-characters"),attributes.get("cameraTokenHash"));
    }

    private static byte[] readOneFrame(VideoStreamingController controller,String authorization) throws Exception {
        ByteArrayOutputStream captured=new ByteArrayOutputStream();
        OutputStream output=new OutputStream() {
            @Override public void write(int value) { captured.write(value); }
            @Override public void write(byte[] bytes) { captured.writeBytes(bytes); }
            @Override public void flush() throws IOException { throw new IOException("done"); }
        };
        controller.getVideoFeed(authorization).getBody().writeTo(output);
        byte[] all=captured.toByteArray();
        int headerEnd=new String(all,StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n")+4;
        return java.util.Arrays.copyOfRange(all,headerEnd,all.length-4);
    }

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
