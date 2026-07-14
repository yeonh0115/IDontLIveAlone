package com.example.smart_door_security_server; // 💡 기존 클래스들의 실제 패키지 경로에 맞게 이 부분을 똑같이 맞춰주세요.

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;

@RestController
@CrossOrigin(origins = "*") // 💡 웹 브라우저나 앱에서 CORS 에러가 발생하는 것을 방지합니다.
public class VideoStreamingController {

    // 💡 라즈베리파이(로컬)가 보낸 최신 JPEG 이미지를 임시 보관할 바이트 배열
    private static volatile byte[] currentFrame = null;
    private static volatile long lastUpdateTime = 0;

    /**
     * ⚡ [추가] 웹소켓 핸들러(CameraWebSocketHandler)에서 리플렉션 없이 
     * 안전하고 빠르게 프레임 데이터를 직접 갱신하기 위한 static 메서드입니다.
     */
    public static void updateFrameDirectly(byte[] imageBytes) {
        currentFrame = imageBytes;
        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * 1. 로컬 라즈베리파이(fin_camera.py)가 비디오 프레임을 업로드하는 API
     */
    @PostMapping(value = "/upload_frame", consumes = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<String> uploadFrame(@RequestBody byte[] imageBytes) {
        currentFrame = imageBytes;
        lastUpdateTime = System.currentTimeMillis();
        return new ResponseEntity<>("{\"status\":\"success\"}", HttpStatus.OK);
    }

    /**
     * 2. 스마트폰 앱이 MJPEG 형식의 실시간 카메라 비디오를 받아가는 스트리밍 API
     */
    @GetMapping("/video_feed")
    public ResponseEntity<StreamingResponseBody> getVideoFeed() {
        
        // MJPEG(Motion JPEG) 표준에 부합하도록 멀티파트(boundary=frame) 타입 지정
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "multipart/x-mixed-replace; boundary=frame");
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache, private, max-age=0, no-store, must-revalidate");
        headers.set(HttpHeaders.PRAGMA, "no-cache");

        StreamingResponseBody responseBody = new StreamingResponseBody() {
            @Override
            public void writeTo(OutputStream out) throws IOException {
                while (true) {
                    long now = System.currentTimeMillis();
                    
                    // 💡 카메라 전송이 끊겼거나(3초 이상 무응답) 프레임이 비어있으면 0.1초 쉬었다가 다시 확인
                    if (currentFrame == null || (now - lastUpdateTime > 3000)) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }

                    try {
                        // MJPEG 경계(boundary) 및 헤더 작성
                        out.write(("--frame\r\n" +
                                   "Content-Type: image/jpeg\r\n" +
                                   "Content-Length: " + currentFrame.length + "\r\n\r\n").getBytes());
                        
                        // 실제 JPEG 이미지 바이너리 데이터 출력
                        out.write(currentFrame);
                        out.write("\r\n\r\n".getBytes());
                        out.flush(); // 即시 버퍼를 밀어서 스마트폰 앱으로 지연 없이 전송
                        
                    } catch (IOException e) {
                        // 스마트폰 앱이 화면을 끄거나 연결을 해제하면 전송 루프를 탈출하여 리소스를 해제합니다.
                        break;
                    }

                    // 💡 전송 속도 조절 (약 20 FPS 전송으로 스프링 부트 메모리/CPU 부하를 제어합니다)
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        };

        return new ResponseEntity<>(responseBody, headers, HttpStatus.OK);
    }
}
