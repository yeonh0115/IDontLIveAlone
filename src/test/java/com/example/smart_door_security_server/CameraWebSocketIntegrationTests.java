package com.example.smart_door_security_server;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:camera-websocket-test;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1"
})
class CameraWebSocketIntegrationTests {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired AppSessionService sessions;
    @Autowired DeviceRegistrationService devices;
    @Autowired PairedDeviceRepository registered;
    @Autowired VideoStreamingController video;

    enum Revocation { UNLINK, TOKEN_CHANGE, DELETE }
    @ParameterizedTest @EnumSource(Revocation.class)
    void realAuthenticatedWebSocketPublishesFirstFrameThenRejectsRevokedIdentity(Revocation revocation) throws Exception {
        User user=new User(); user.setUserId("ws-"+UUID.randomUUID()); user=users.saveAndFlush(user);
        String app="Bearer "+sessions.issue(user.getUserNo()).sessionToken();
        String camera="Bearer "+TokenSecrets.generate();
        var pending=devices.enroll(camera,new DeviceRegistrationService.EnrollRequest(DeviceRole.CAMERA,"WS test"),"ws-test");
        devices.claim(app,pending.pairingCode(),"ws-test");
        CompletableFuture<Integer> closed=new CompletableFuture<>();
        WebSocket.Listener listener=new WebSocket.Listener() {
            @Override public void onOpen(WebSocket socket) { socket.request(1); }
            @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason) {
                closed.complete(code); return null;
            }
            @Override public void onError(WebSocket socket,Throwable error) { closed.completeExceptionally(error); }
        };
        byte[] frame={10,20,30,40};
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                var workers=Executors.newSingleThreadExecutor()) {
            WebSocket socket=client.newWebSocketBuilder().header("Authorization",camera)
                    .buildAsync(URI.create("ws://localhost:"+port+"/ws/camera"),listener).get(5,TimeUnit.SECONDS);
            var read=workers.submit(() -> readOneFrame(app));
            try {
                socket.sendBinary(ByteBuffer.wrap(frame),true).get(5,TimeUnit.SECONDS);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!read.isDone() && !closed.isDone() && System.nanoTime()<deadline) Thread.sleep(10);
                assertThat(closed.isDone()).as("Server closed before publishing first frame: %s",closed.getNow(null)).isFalse();
                assertThat(read.get(1,TimeUnit.SECONDS)).isEqualTo(frame);
                assertThat(socket.isOutputClosed()).isFalse();
                switch (revocation) {
                    case UNLINK -> devices.unlink(app,pending.deviceId());
                    case TOKEN_CHANGE -> {
                        var stored=registered.findById(pending.deviceId()).orElseThrow();
                        stored.setTokenHash(TokenSecrets.hash(TokenSecrets.generate())); registered.saveAndFlush(stored);
                    }
                    case DELETE -> registered.deleteById(pending.deviceId());
                }
                Thread.sleep(2100); // Wait for the documented two-second identity recheck window.
                socket.sendBinary(ByteBuffer.wrap(frame),true).get(5,TimeUnit.SECONDS);
                assertThat(closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
            } finally {
                read.cancel(true);
                socket.abort();
                VideoStreamingController.clearOwnerFrame(user.getUserNo());
            }
        }
    }

    private byte[] readOneFrame(String authorization) throws Exception {
        ByteArrayOutputStream captured=new ByteArrayOutputStream();
        OutputStream output=new OutputStream() {
            @Override public void write(int value) { captured.write(value); }
            @Override public void write(byte[] value) { captured.writeBytes(value); }
            @Override public void flush() throws IOException { throw new IOException("test frame received"); }
        };
        video.getVideoFeed(authorization).getBody().writeTo(output);
        byte[] response=captured.toByteArray();
        int headerEnd=new String(response,StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n")+4;
        return Arrays.copyOfRange(response,headerEnd,response.length-4);
    }
}
