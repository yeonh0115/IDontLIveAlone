import subprocess
import threading
import time
import ssl
import websocket
import datetime
import requests
from flask import Flask, Response, jsonify

app = Flask(__name__)

global_frame = None
camera_lock = threading.Lock()

# Render 서버 설정
RENDER_WS_URL = "wss://idontlivealone.onrender.com/ws/camera"
RENDER_UPLOAD_URL = "https://idontlivealone.onrender.com/api/upload"


def capture_camera():
    global global_frame

    # 320x240 해상도로 프레임 데이터 최소화 (15fps 수준)
    cmd = [
        'rpicam-vid',
        '-t', '0',
        '--width', '320',
        '--height', '240',
        '--framerate', '15',
        '--codec', 'mjpeg',
        '--quality', '40',
        '--nopreview',
        '-n',
        '--flush',
        '-o', '-'
    ]

    process = None
    try:
        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            bufsize=0
        )

        buffer = b""

        while True:
            chunk = process.stdout.read(4096)
            if not chunk:
                time.sleep(0.001)
                continue
            buffer += chunk

            while True:
                start = buffer.find(b'\xff\xd8')
                end = buffer.find(b'\xff\xd9')

                if start != -1 and end != -1:
                    if start < end:
                        jpg = buffer[start:end + 2]
                        buffer = buffer[end + 2:]

                        with camera_lock:
                            global_frame = jpg
                    else:
                        buffer = buffer[start:]
                    continue
                break

            if len(buffer) > 100000:
                buffer = b""

    except Exception as capture_err:
        print(f"🚨 카메라 프로세스 오류 발생: {capture_err}")
    finally:
        if process:
            try:
                process.kill()
                process.wait()
            except:
                pass
            print("🎥 카메라 캡처 서브프로세스가 안전하게 종료되었습니다.")


# 웹소켓 통해 클라우드로 실시간 스트리밍 전송
def upload_to_cloud_websocket():
    print("[시스템] 클라우드 웹소켓 전송 스레드 가동...")
    ws = None
    
    while True:
        try:
            ws = websocket.create_connection(
                RENDER_WS_URL, 
                timeout=3.0,
                sslopt={"cert_reqs": ssl.CERT_NONE, "check_hostname": False}
            )
            print("[웹소켓] ⚡ 클라우드 서버와 웹소켓 연결 성공!")
            
            last_sent_frame = None
            
            while True:
                frame = None
                with camera_lock:
                    if global_frame is not None:
                        frame = global_frame
                
                if frame is not None and frame != last_sent_frame:
                    try:
                        ws.send_binary(frame)
                        last_sent_frame = frame
                    except (websocket.WebSocketConnectionClosedException, websocket.WebSocketTimeoutException):
                        print("[웹소켓] 전송 도중 연결이 끊겼습니다.")
                        break
                
                time.sleep(0.06)
                
        except (websocket.WebSocketException, Exception) as e:
            print(f"[웹소켓 알림] 연결 끊김 또는 오류 ({e}). 3초 후 재연결 시도...")
            if ws:
                try:
                    ws.close()
                except:
                    pass
                ws = None
            time.sleep(3)


# 📌 [수정 완료] 이벤트 발생 시 라즈베리파이 최신 프레임을 스프링 부트 서버로 전송하고 URL을 반환하는 함수
def upload_event_image(log_id=None):
    """
    센서 감지/도어락 이벤트 발생 시 호출하는 함수 (성공 여부 및 이미지 URL 반환)
    """
    current_frame = None
    with camera_lock:
        if global_frame is not None:
            current_frame = global_frame

    if current_frame is None:
        print("🚨 [캡처 에러] 현재 카메라 프레임이 준비되지 않았습니다.")
        return False, None

    if not log_id:
        log_id = str(int(time.time()))

    today_date = datetime.datetime.now().strftime("%Y-%m-%d")

    try:
        files = {
            'file': (f"event_{log_id}.jpg", current_frame, 'image/jpeg')
        }
        data = {
            'log_id': log_id,
            'date': today_date
        }

        print(f"📸 [서버 전송 시작] 날짜: {today_date}, Log ID: {log_id}")
        response = requests.post(RENDER_UPLOAD_URL, files=files, data=data, timeout=8)

        if response.status_code == 200:
            res_json = response.json()
            print(f"✅ [업로드 성공] 서버 응답: {res_json}")
            
            # Render 서버 응답 키값 추출 (imageUrl, url, image_url 중 존재하는 값 사용)
            img_url = res_json.get("imageUrl") or res_json.get("url") or res_json.get("image_url")
            return True, img_url
        else:
            print(f"❌ [업로드 실패] 상태 코드: {response.status_code}, 내용: {response.text}")
            return False, None

    except Exception as e:
        print(f"🚨 [네트워크 오류] 서버 전송 예외 발생: {e}")
        return False, None


def generate_frames():
    last_frame = None
    while True:
        frame = None
        with camera_lock:
            if global_frame is not None:
                frame = global_frame
        
        if frame is None:
            time.sleep(0.01)
            continue
            
        if frame != last_frame:
            last_frame = frame
            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n\r\n' +
                   frame +
                   b'\r\n\r\n')
        
        time.sleep(0.05)


@app.route('/')
def index():
    return '''
    <!DOCTYPE html>
    <html>
    <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <title>Realtime Camera Feed</title>
    </head>
    <body style="margin:0; padding:0; background:black; overflow:hidden; width:100vw; height:100vh;">
        <img src="/video_feed" style="width:100%; height:100%; object-fit:cover; margin:0; padding:0; display:block;">
    </body>
    </html>
    '''


@app.route('/video_feed')
def video_feed():
    return Response(
        generate_frames(),
        mimetype='multipart/x-mixed-replace; boundary=frame'
    )


@app.route('/snapshot')
def snapshot():
    frame_bytes = None
    with camera_lock:
        if global_frame is not None:
            frame_bytes = global_frame
            
    if frame_bytes is None:
        return "Camera frame not ready", 503
        
    return Response(frame_bytes, mimetype='image/jpeg')


# 📌 [수정 완료] 라즈베리파이 B에서 호출하는 트리거 엔드포인트 (JSON 반환)
@app.route('/trigger_event')
def trigger_event():
    success, image_url = upload_event_image()
    if success:
        return jsonify({
            "status": "success",
            "image_url": image_url
        }), 200
    else:
        return jsonify({
            "status": "error",
            "message": "Upload to cloud failed"
        }), 500


if __name__ == "__main__":
    # 카메라 캡처 스레드 가동
    threading.Thread(target=capture_camera, daemon=True).start()
    # 웹소켓 전송 스레드 가동
    threading.Thread(target=upload_to_cloud_websocket, daemon=True).start()
    
    # 5002 포트에서 Flask 서버 구동
    app.run(host='0.0.0.0', port=5002, threaded=True, debug=False)