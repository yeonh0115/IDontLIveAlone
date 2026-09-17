"""Shared local capture and direct WebRTC video for the door Raspberry Pi."""
import datetime
import os
import select
import subprocess
import threading
import time

import requests
from flask import Flask, Response, jsonify, request

from pi_runtime import CaptureQueue, LatestFrame, Settings, event_log_id, upload_fields
from webrtc_transport import WebRtcWorker

app = Flask(__name__)
settings = Settings()
frames = LatestFrame()
RENDER_UPLOAD_URL = settings.api_url("/api/upload")
video_transport = WebRtcWorker(settings, frames, logger=lambda message: print(message, flush=True))

CAPTURE_FRAME_TIMEOUT = 10.0
CAPTURE_POLL_INTERVAL = 0.5
CAPTURE_STOP_TIMEOUT = 2.0
MAX_CAPTURE_JPEG_BYTES = 1_000_000


def read_camera_frames(process, stopped, clock=time.monotonic):
    """Read the Linux pipe without waiting forever for a live but stalled child."""
    descriptor = process.stdout.fileno()
    os.set_blocking(descriptor, False)
    buffer = bytearray()
    last_frame_at = clock()
    while not stopped.is_set():
        remaining = CAPTURE_FRAME_TIMEOUT - (clock() - last_frame_at)
        if remaining <= 0:
            raise TimeoutError("rpicam-vid produced no complete JPEG for 10 seconds")
        try:
            readable, _, _ = select.select([descriptor], [], [], min(CAPTURE_POLL_INTERVAL, remaining))
            if not readable:
                if process.poll() is not None:
                    raise RuntimeError("rpicam-vid stopped")
                continue
            chunk = os.read(descriptor, 64 * 1024)
        except (BlockingIOError, InterruptedError):
            continue
        if not chunk:
            raise RuntimeError("rpicam-vid ended its output stream")
        buffer.extend(chunk)
        while buffer:
            start = buffer.find(b"\xff\xd8")
            if start < 0:
                # Keep a split SOI marker, without accumulating unrelated output.
                buffer[:] = b"\xff" if buffer[-1] == 0xff else b""
                break
            if start:
                del buffer[:start]
            end = buffer.find(b"\xff\xd9", 2)
            next_start = buffer.find(b"\xff\xd8", 2)
            if next_start >= 0 and (end < 0 or next_start < end):
                # Recover from an incomplete image followed by a new JPEG.
                del buffer[:next_start]
                continue
            if end < 0:
                if len(buffer) > MAX_CAPTURE_JPEG_BYTES:
                    buffer[:] = b"\xff" if buffer[-1] == 0xff else b""
                break
            size = end + 2
            if 4 < size <= MAX_CAPTURE_JPEG_BYTES:
                frames.put(bytes(memoryview(buffer)[:size]))
                last_frame_at = clock()
            del buffer[:size]


def reap_camera(process):
    """Release the camera before a replacement process is allowed to start."""
    try:
        if process.poll() is None:
            try:
                process.terminate()
            except ProcessLookupError:
                pass
        try:
            process.wait(timeout=CAPTURE_STOP_TIMEOUT)
        except subprocess.TimeoutExpired:
            try:
                process.kill()
            except ProcessLookupError:
                pass
            process.wait(timeout=CAPTURE_STOP_TIMEOUT)
    finally:
        if process.stdout:
            process.stdout.close()


def capture_camera(stopped=None):
    stopped = stopped if stopped is not None else threading.Event()
    command = [
        "rpicam-vid", "-t", "0", "--width", str(settings.camera_width), "--height", str(settings.camera_height),
        "--framerate", str(settings.camera_capture_fps), "--codec", "mjpeg", "--quality", str(settings.camera_jpeg_quality),
        "--nopreview", "--flush", "-o", "-",
    ]
    while not stopped.is_set():
        process = None
        frames.clear()
        try:
            process = subprocess.Popen(command, stdout=subprocess.PIPE, bufsize=0)
            read_camera_frames(process, stopped)
        except Exception as error:
            print(f"[camera] Capture stopped: {error}; restarting in 2 seconds", flush=True)
        finally:
            frames.clear()
            if process is not None:
                try:
                    reap_camera(process)
                except Exception as error:
                    # Never start another camera owner when the old child cannot be reaped.
                    print(f"[camera] Child cleanup failed ({type(error).__name__}); capture stopped", flush=True)
                    return
        stopped.wait(2)

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

@app.route("/video_status")
def video_status():
    return jsonify(**video_transport.status())

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
    video_transport.run()


if __name__ == "__main__":
    threading.Thread(target=capture_camera, daemon=True).start()
    threading.Thread(target=start_cloud_transport, daemon=True).start()
    app.run(host="0.0.0.0", port=5002, threaded=True, debug=False)
