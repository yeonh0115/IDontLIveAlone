"""Camera capture, local MJPEG and Render transport for the door Raspberry Pi."""
import datetime
import os
import socket
import ssl
import subprocess
import threading
import time

import requests
import websocket
from flask import Flask, Response, jsonify, request

from pi_runtime import AckVideoSender, CaptureQueue, LatestFrame, Settings, event_log_id, require_camera_ack, upload_fields

app = Flask(__name__)
settings = Settings()
frames = LatestFrame()
RENDER_WS_URL = settings.websocket_url
RENDER_UPLOAD_URL = settings.api_url("/api/upload")

def capture_camera():
    command = [
        "rpicam-vid", "-t", "0", "--width", str(settings.camera_width), "--height", str(settings.camera_height),
        "--framerate", str(settings.camera_capture_fps), "--codec", "mjpeg", "--quality", str(settings.camera_jpeg_quality),
        "--nopreview", "--flush", "-o", "-",
    ]
    while True:
        process = None
        frames.clear()
        try:
            process = subprocess.Popen(command, stdout=subprocess.PIPE, bufsize=0)
            buffer = b""
            while True:
                chunk = process.stdout.read(4096)
                if not chunk:
                    raise RuntimeError("rpicam-vid ended its output stream")
                buffer += chunk
                while True:
                    start = buffer.find(b"\xff\xd8")
                    if start < 0:
                        break
                    end = buffer.find(b"\xff\xd9", start + 2)
                    if end < 0:
                        buffer = buffer[start:]
                        break
                    frames.put(buffer[start:end + 2])
                    buffer = buffer[end + 2:]
                if len(buffer) > 1_000_000:
                    buffer = b""
        except Exception as error:
            print(f"[camera] Capture stopped: {error}; restarting in 2 seconds", flush=True)
        finally:
            frames.clear()
            if process is not None:
                if process.poll() is None:
                    process.kill()
                process.wait()
                if process.stdout:
                    process.stdout.close()
        time.sleep(2)

def upload_to_cloud_websocket():
    while True:
        connection = None
        try:
            ssl_options = {"cert_reqs": ssl.CERT_REQUIRED, "check_hostname": True}
            ca_bundle = os.environ.get("SSL_CERT_FILE") or os.environ.get("REQUESTS_CA_BUNDLE")
            if ca_bundle:
                ssl_options["ca_certs"] = ca_bundle
            headers = dict(settings.auth_headers())
            headers["X-Camera-Ack"] = "1"
            socket_options = [(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)] if hasattr(socket, "TCP_NODELAY") else []
            connection = websocket.create_connection(
                RENDER_WS_URL, timeout=5, sslopt=ssl_options, header=headers, sockopt=socket_options,
            )
            require_camera_ack(connection)
            sender = AckVideoSender(frames, settings, logger=lambda message: print(message, flush=True))
            print("[camera] Render WebSocket connected (ACK v1)", flush=True)
            while True:
                sender.step(connection)
                time.sleep(0.005)
        except Exception as error:
            print(f"[camera] WebSocket disconnected: {type(error).__name__}; retrying in 3 seconds", flush=True)
        finally:
            if connection is not None:
                try:
                    connection.shutdown()  # Discard queued bytes; do not wait for a close handshake.
                except Exception:
                    pass
        time.sleep(3)

def upload_event_image(log_id=None):
    if not settings.event_photos_enabled:
        return False, None
    frame, _ = frames.get()
    if frame is None:
        return False, None
    try:
        data = upload_fields(settings.user_no, log_id, datetime.date.today().isoformat())
        response = requests.post(
            RENDER_UPLOAD_URL,
            files={"file": (f"event_{data['log_id']}.jpg", frame, "image/jpeg")},
            data=data,
            headers=settings.auth_headers(),
            timeout=10,
        )
        if response.status_code == 200:
            body = response.json()
            url = body.get("imageUrl") or body.get("url") or body.get("image_url")
            if url:
                return True, url
        print(f"[camera] Event upload failed: HTTP {response.status_code}", flush=True)
    except (ValueError, requests.RequestException) as error:
        print(f"[camera] Event upload failed: {error}", flush=True)
    return False, None


class CaptureClient:
    @staticmethod
    def parsed(response):
        try:
            return response.status_code, response.json()
        except ValueError:
            return response.status_code, None

    def claim(self):
        return self.parsed(requests.get(settings.api_url("/api/camera/captures"), headers=settings.auth_headers(), timeout=10))

    def upload(self, task, frame):
        data = upload_fields(task["userNo"], task["eventId"], task["eventDate"])
        data.update({"captureTaskId": task["captureTaskId"], "leaseToken": task["leaseToken"]})
        return self.parsed(requests.post(
            RENDER_UPLOAD_URL, data=data, headers=settings.auth_headers(),
            files={"file": (f"event_{task['eventId']}.jpg", frame, "image/jpeg")}, timeout=10,
        ))

    def failed(self, task, message):
        return self.parsed(requests.post(
            settings.api_url(f"/api/camera/captures/{task['captureTaskId']}/result"),
            json={"leaseToken": task["leaseToken"], "status": "error", "message": message},
            headers=settings.auth_headers(), timeout=5,
        ))


def poll_capture_queue():
    queue = CaptureQueue(settings.data_dir / "capture_state", settings.user_no, CaptureClient(), lambda: frames.get()[0])
    refreshed = 0.0
    while True:
        try:
            if time.monotonic() >= refreshed:
                status = settings.device.refresh()
                if not status["paired"] or settings.device.user_no != settings.user_no:
                    raise RuntimeError("Camera pairing changed; service restart/review required")
                refreshed = time.monotonic() + 30
            queue.step()
        except Exception as error:
            # Do not print network exception text, which may expose proxy credentials.
            print(f"[capture] {type(error).__name__}; saved capture retained, retrying", flush=True)
        time.sleep(2)

def generate_frames():
    last_sequence = -1
    while True:
        frame, sequence = frames.get()
        if frame is not None and sequence != last_sequence:
            last_sequence = sequence
            yield b"--frame\r\nContent-Type: image/jpeg\r\n\r\n" + frame + b"\r\n\r\n"
        time.sleep(0.05)

@app.route("/")
def index():
    return '<html><meta name="viewport" content="width=device-width,initial-scale=1"><body style="margin:0;background:black"><img src="/video_feed" style="width:100%;height:100vh;object-fit:cover"></body></html>'

@app.route("/video_feed")
def video_feed():
    return Response(generate_frames(), mimetype="multipart/x-mixed-replace; boundary=frame")

@app.route("/snapshot")
def snapshot():
    frame, _ = frames.get()
    if frame is None:
        return "Camera frame not ready", 503
    return Response(frame, mimetype="image/jpeg")

@app.route("/trigger_event")
def trigger_event():
    if not settings.event_photos_enabled:
        return jsonify(status="disabled", message="Event photo storage is disabled"), 410
    if settings.user_no is None:
        return jsonify(status="error", message="Set USER_NO before uploading event images"), 503
    expected_user_no = request.args.get("expected_user_no")
    if expected_user_no is not None:
        if not expected_user_no.isascii() or not expected_user_no.isdigit() or int(expected_user_no) <= 0:
            return jsonify(status="error", message="expected_user_no must be a positive user number"), 400
        if int(expected_user_no) != settings.user_no:
            return jsonify(status="error", message="Pi A USER_NO does not match the event owner"), 403
    try:
        log_id = event_log_id(request.args.get("log_id"))
    except ValueError as error:
        return jsonify(status="error", message=str(error)), 400
    success, image_url = upload_event_image(log_id)
    if success:
        return jsonify(status="success", image_url=image_url, log_id=log_id, user_no=settings.user_no), 200
    return jsonify(status="error", message="Camera unavailable or upload to cloud failed", log_id=log_id), 502

def start_cloud_transport():
    settings.activate_pairing()
    threading.Thread(target=upload_to_cloud_websocket, daemon=True).start()
    if settings.device and settings.event_photos_enabled:
        threading.Thread(target=poll_capture_queue, daemon=True).start()


if __name__ == "__main__":
    threading.Thread(target=capture_camera, daemon=True).start()
    threading.Thread(target=start_cloud_transport, daemon=True).start()
    app.run(host="0.0.0.0", port=5002, threaded=True, debug=False)
