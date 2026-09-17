package com.example.smart_door_security_server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:video-pull-test;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1"
})
class VideoPullProtocolTests {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired AppSessionService sessions;
    @Autowired AppSessionRepository sessionRows;
    @Autowired DeviceRegistrationService devices;
    record Account(Integer owner, String authorization, String hash) { }
    record Reply(String text, byte[] binary) { }

    @Test void cameraAckIsNegotiatedInRealHandshakeAndOnlySentAfterFramePublication() throws Exception {
        Account account=account(); String camera=pairCamera(account);
        byte[] first={1,2,3,4}; byte[] second={5,6,7,8};
        try {
            try(CameraSocket socket=new CameraSocket(camera,true)) {
                assertThat(socket.headers.toLowerCase(Locale.ROOT)).contains("x-camera-ack: 1\r\n");
                socket.sendBinary(first);
                assertThat(socket.readText()).isEqualTo("ack");
                assertThat(VideoStreamingController.latestOwnerFrame(account.owner()).jpeg()).isEqualTo(first);
            }
            VideoStreamingController.clearOwnerFrame(account.owner());
            try(CameraSocket legacy=new CameraSocket(camera,false)) {
                assertThat(legacy.headers.toLowerCase(Locale.ROOT)).doesNotContain("x-camera-ack:");
                legacy.sendBinary(second);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(VideoStreamingController.latestOwnerFrame(account.owner())==null && System.nanoTime()<deadline) Thread.sleep(10);
                assertThat(VideoStreamingController.latestOwnerFrame(account.owner()).jpeg()).isEqualTo(second);
                legacy.socket.setSoTimeout(250);
                assertThatThrownBy(() -> legacy.socket.getInputStream().read()).isInstanceOf(SocketTimeoutException.class);
            }
        } finally { VideoStreamingController.clearOwnerFrame(account.owner()); }
    }

    @Test void viewerRequiresAnAppSessionAndRejectsDeviceOrMissingCredentials() throws Exception {
        Account account=account(); String camera=pairCamera(account);
        try(var client=HttpClient.newHttpClient()) {
            assertRejected(client,null);
            assertRejected(client,camera);
            sessions.revoke(account.authorization());
            assertRejected(client,account.authorization());
        }
    }

    @Test void viewerPullReturnsOnlyOwnersFreshFrameOnceAndDoesNotPushUnrequestedData() throws Exception {
        Account owner=account(), other=account();
        try(var client=HttpClient.newHttpClient()) {
            Listener replies=new Listener(); WebSocket socket=openViewer(client,owner.authorization(),replies);
            try {
                VideoStreamingController.updateOwnerFrame(other.owner(),new byte[]{9});
                assertThat(next(socket,replies).text()).isEqualTo("waiting");
                VideoStreamingController.updateOwnerFrame(owner.owner(),new byte[]{1,2});
                assertThat(replies.replies.poll(150,TimeUnit.MILLISECONDS)).isNull();
                assertThat(next(socket,replies).binary()).isEqualTo(new byte[]{1,2});
                assertThat(next(socket,replies).text()).isEqualTo("waiting");
                VideoStreamingController.updateOwnerFrame(other.owner(),new byte[]{8});
                assertThat(next(socket,replies).text()).isEqualTo("waiting");
                VideoStreamingController.updateOwnerFrame(owner.owner(),new byte[]{3,4});
                assertThat(next(socket,replies).binary()).isEqualTo(new byte[]{3,4});
                VideoStreamingController.updateOwnerFrame(owner.owner(),new byte[]{5,6});
                Thread.sleep(1100);
                assertThat(next(socket,replies).text()).isEqualTo("waiting");
            } finally { socket.abort(); }
        } finally {
            VideoStreamingController.clearOwnerFrame(owner.owner()); VideoStreamingController.clearOwnerFrame(other.owner());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void viewerStopsOnRevocationOrExpiryWithoutReadingRecycledHandshakeHeaders(boolean expiry) throws Exception {
        Account owner=account();
        Instant expiresAt=Instant.now().plusMillis(1500);
        if(expiry) {
            var row=sessionRows.findById(owner.hash()).orElseThrow(); row.setExpiresAt(expiresAt); sessionRows.saveAndFlush(row);
        }
        try(var client=HttpClient.newHttpClient()) {
            Listener replies=new Listener(); WebSocket socket=openViewer(client,owner.authorization(),replies);
            try {
                VideoStreamingController.updateOwnerFrame(owner.owner(),new byte[]{1});
                assertThat(next(socket,replies).binary()).isEqualTo(new byte[]{1});
                if(expiry) Thread.sleep(Math.max(0,Duration.between(Instant.now(),expiresAt).toMillis())+50);
                else { sessions.revoke(owner.authorization()); Thread.sleep(2100); }
                socket.sendText("next",true).get(3,TimeUnit.SECONDS);
                assertThat(replies.closed.get(3,TimeUnit.SECONDS)).isEqualTo(1008);
                assertThat(replies.replies).isEmpty();
            } finally { socket.abort(); }
        } finally { VideoStreamingController.clearOwnerFrame(owner.owner()); }
    }

    @Test void viewerRejectsOversizedFrameInsteadOfSendingBeyondNegotiatedLimit() throws Exception {
        Account owner=account();
        try(var client=HttpClient.newHttpClient()) {
            Listener replies=new Listener(); WebSocket socket=openViewer(client,owner.authorization(),replies);
            try {
                VideoStreamingController.updateOwnerFrame(owner.owner(),new byte[512*1024+1]);
                socket.sendText("next",true).get(3,TimeUnit.SECONDS);
                assertThat(replies.closed.get(3,TimeUnit.SECONDS)).isEqualTo(1009);
                assertThat(replies.replies).isEmpty();
            } finally { socket.abort(); }
        } finally { VideoStreamingController.clearOwnerFrame(owner.owner()); }
    }

    private Account account() {
        User user=new User(); user.setUserId("pull-"+UUID.randomUUID()); user=users.saveAndFlush(user);
        String token=sessions.issue(user.getUserNo()).sessionToken(); VideoStreamingController.clearOwnerFrame(user.getUserNo());
        return new Account(user.getUserNo(),"Bearer "+token,TokenSecrets.hash(token));
    }
    private String pairCamera(Account owner) {
        String token="Bearer "+TokenSecrets.generate();
        var pending=devices.enroll(token,new DeviceRegistrationService.EnrollRequest(DeviceRole.CAMERA,"Pull test"),"pull-test");
        devices.claim(owner.authorization(),pending.pairingCode(),"pull-test"); return token;
    }
    private WebSocket openViewer(HttpClient client,String authorization,Listener listener) throws Exception {
        var builder=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3));
        if(authorization!=null) builder.header("Authorization",authorization);
        return builder.buildAsync(URI.create("ws://localhost:"+port+"/ws/viewer"),listener).get(3,TimeUnit.SECONDS);
    }
    private void assertRejected(HttpClient client,String authorization) {
        assertThatThrownBy(() -> openViewer(client,authorization,new Listener()))
                .isInstanceOfSatisfying(ExecutionException.class,ex -> assertThat(ex.getCause())
                        .isInstanceOfSatisfying(WebSocketHandshakeException.class,
                                handshake -> assertThat(handshake.getResponse().statusCode()).isEqualTo(401)));
    }
    private Reply next(WebSocket socket,Listener replies) throws Exception {
        socket.sendText("next",true).get(3,TimeUnit.SECONDS);
        Reply reply=replies.replies.poll(3,TimeUnit.SECONDS);
        assertThat(reply).isNotNull(); return reply;
    }
    static class Listener implements WebSocket.Listener {
        final BlockingQueue<Reply> replies=new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed=new CompletableFuture<>();
        final ByteArrayOutputStream binary=new ByteArrayOutputStream();
        final StringBuilder text=new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last) {
            text.append(data); if(last) { replies.add(new Reply(text.toString(),null)); text.setLength(0); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onBinary(WebSocket socket,ByteBuffer data,boolean last) {
            byte[] part=new byte[data.remaining()]; data.get(part); binary.writeBytes(part);
            if(last) { replies.add(new Reply(null,binary.toByteArray())); binary.reset(); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket,int status,String reason) { closed.complete(status); return null; }
        @Override public void onError(WebSocket socket,Throwable error) { closed.completeExceptionally(error); }
    }
    class CameraSocket implements AutoCloseable {
        final Socket socket=new Socket();
        final String headers;
        CameraSocket(String authorization,boolean ack) throws IOException {
            socket.connect(new InetSocketAddress("localhost",port),3000); socket.setSoTimeout(3000);
            String request="GET /ws/camera HTTP/1.1\r\nHost: localhost:"+port+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    +"Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nAuthorization: "+authorization+"\r\n"
                    +(ack?"X-Camera-Ack: 1\r\n":"")+"\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            StringBuilder received=new StringBuilder();
            while(!received.toString().endsWith("\r\n\r\n")) {
                int next=socket.getInputStream().read(); if(next<0 || received.length()>8192) throw new IOException("Incomplete upgrade");
                received.append((char)next);
            }
            headers=received.toString(); assertThat(headers).startsWith("HTTP/1.1 101");
        }
        void sendBinary(byte[] frame) throws IOException {
            if(frame.length>125) throw new IllegalArgumentException("Small test frame required");
            OutputStream output=socket.getOutputStream(); output.write(0x82); output.write(0x80|frame.length);
            byte[] mask={1,2,3,4}; output.write(mask);
            for(int i=0;i<frame.length;i++) output.write(frame[i]^mask[i%4]); output.flush();
        }
        String readText() throws IOException {
            InputStream input=socket.getInputStream(); assertThat(input.read()).isEqualTo(0x81);
            int length=input.read(); assertThat(length).isBetween(0,125);
            return new String(input.readNBytes(length),StandardCharsets.UTF_8);
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
