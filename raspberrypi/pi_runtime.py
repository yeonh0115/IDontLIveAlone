"""Hardware-independent configuration and durable task state for the door Pi."""
import hashlib
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import shutil
import threading
import time
from urllib.parse import urljoin, urlparse
import uuid

DEFAULT_SERVER = "https://idontlivealone.onrender.com"
MAX_STREAM_JPEG_BYTES = 512 * 1024

def bounded_setting(env, name, default, minimum, maximum, integer=False):
    try:
        value = int(env.get(name, str(default))) if integer else float(env.get(name, str(default)))
    except (ValueError, TypeError):
        raise ValueError(f"Invalid {name}") from None
    if not math.isfinite(value) or not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value

class Settings:
    def __init__(self, environ=None, source_dir=None):
        env = os.environ if environ is None else environ
        source = Path(source_dir or Path(__file__).resolve().parent)
        self.server_url = env.get("RENDER_SERVER_URL", DEFAULT_SERVER).rstrip("/")
        parsed = urlparse(self.server_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError("RENDER_SERVER_URL must be an absolute HTTP(S) URL")
        self.stream_url = env.get("STREAM_URL", "http://127.0.0.1:5002/video_feed")
        self.device_id = env.get("DEVICE_ID", "door-camera-a").strip()
        if not self.device_id:
            raise ValueError("DEVICE_ID must not be empty")
        raw_user = env.get("USER_NO", "").strip()
        self.user_no = None
        self.pairing_enabled = env.get("DEVICE_PAIRING", "0" if raw_user else "1").strip() != "0"
        self.device = None
        self.source_dir = source
        self.event_photos_enabled = env.get("EVENT_PHOTOS_ENABLED", "false").strip().lower() in ("1", "true", "yes")
        self.camera_width = bounded_setting(env, "CAMERA_WIDTH", 640, 160, 1280, True)
        self.camera_height = bounded_setting(env, "CAMERA_HEIGHT", 480, 120, 720, True)
        if self.camera_width % 2 or self.camera_height % 2:
            raise ValueError("Camera width and height must be even")
        self.camera_jpeg_quality = bounded_setting(env, "CAMERA_JPEG_QUALITY", 70, 1, 100, True)
        self.camera_capture_fps = bounded_setting(env, "CAMERA_CAPTURE_FPS", 15, 1, 30)
        self.camera_cloud_fps = bounded_setting(env, "CAMERA_CLOUD_FPS", 10, 1, self.camera_capture_fps)
        self.camera_ack_timeout = bounded_setting(env, "CAMERA_ACK_TIMEOUT", 2, 0.25, 10)
        self.camera_max_frame_age = bounded_setting(env, "CAMERA_MAX_FRAME_AGE", 0.5, 0.05, 2)
        self.face_threshold = float(env.get("FACE_THRESHOLD", "85"))
        self.face_required_successes = int(env.get("FACE_REQUIRED_SUCCESSES", "4"))
        self.solenoid_pin = int(env.get("SOLENOID_PIN", "23"))
        if not math.isfinite(self.face_threshold) or self.face_threshold <= 0 or not 1 <= self.face_required_successes <= 100 or not 0 <= self.solenoid_pin <= 53:
            raise ValueError("Invalid face threshold, consecutive recognition count or GPIO line")
        if raw_user:
            if not raw_user.isascii() or not raw_user.isdigit() or int(raw_user) <= 0:
                raise ValueError("USER_NO must be a positive integer")
            self.user_no = int(raw_user)
        data_dir = Path(env.get("PI_DATA_DIR", str(source)))
        self.data_dir = data_dir if data_dir.is_absolute() else source / data_dir

    def api_url(self, path):
        return urljoin(self.server_url + "/", path)

    @property
    def websocket_url(self):
        url = self.api_url("/ws/camera")
        return ("wss://" + url[8:]) if url.startswith("https://") else "ws://" + url[7:]

    def task_params(self):
        params = {"device_id": self.device_id}
        if self.user_no is not None:
            params["user_no"] = self.user_no
        return params

    def activate_pairing(self):
        if not self.pairing_enabled:
            return
        self.user_no = None
        import sys
        # The installer copies the same helper beside these files; repo runs share it.
        sys.path.insert(0, str(self.source_dir.parent / "device_client"))
        from device_pairing import device_from_environment
        self.device = device_from_environment("CAMERA", self.source_dir)
        self.device.wait_paired()
        self.user_no, self.device_id = self.device.user_no, self.device.device_id

    def auth_headers(self):
        return self.device.headers() if self.device else {}

def event_log_id(value=None):
    if value is None or value == "":
        return str(uuid.uuid4())
    value = str(value)
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value):
        raise ValueError("log_id must contain 1-100 letters, digits, underscores or hyphens")
    return value

def upload_fields(user_no, log_id, date):
    if user_no is None or isinstance(user_no, bool) or int(user_no) <= 0:
        raise ValueError("Set USER_NO to the account's positive user number before uploading events")
    return {"userNo": str(user_no), "log_id": event_log_id(log_id), "date": date}

class LatestFrame:
    """Non-destructive frame reads let recognition and task requests coexist."""
    def __init__(self, max_age=5.0, clock=time.monotonic):
        self.lock = threading.Lock()
        self.max_age = max_age
        self.clock = clock
        self.frame = None
        self.sequence = 0
        self.updated_at = 0.0

    def put(self, frame):
        with self.lock:
            self.frame = frame
            self.sequence += 1
            self.updated_at = self.clock()

    def get(self, max_age=None):
        with self.lock:
            age_limit = self.max_age if max_age is None else min(self.max_age, max_age)
            if self.frame is None or self.clock() - self.updated_at > age_limit:
                return None, self.sequence
            return self.frame, self.sequence

    def clear(self):
        with self.lock:
            self.frame = None

def face_detection_size(width, height):
    """Keep the original 320x240 -> 160x120 workload as stream quality grows."""
    if width <= 0 or height <= 0:
        raise ValueError("Invalid face frame dimensions")
    scale = min(1.0, 160 / width, 120 / height)
    return max(1, int(width * scale)), max(1, int(height * scale))

class CameraAckUnsupported(RuntimeError):
    pass

class CameraAckInvalid(RuntimeError):
    pass

def require_camera_ack(connection):
    headers = connection.getheaders() or {}
    supported = next((value for key, value in headers.items() if key.lower() == "x-camera-ack"), None)
    if supported != "1":
        raise CameraAckUnsupported("Server does not support camera ACK")

def send_frame_with_ack(connection, frame, timeout, clock=time.monotonic, timer_factory=threading.Timer):
    """One deadline includes all partial socket writes, reads and control frames."""
    if not frame or len(frame) > MAX_STREAM_JPEG_BYTES:
        raise ValueError("Invalid stream JPEG size")
    started = clock()
    deadline = started + timeout
    expired = threading.Event()

    def abort():
        expired.set()
        try:
            connection.abort()  # shutdown(SHUT_RDWR), without a close-handshake wait
        except Exception:
            pass

    def remaining():
        value = deadline - clock()
        if expired.is_set() or value <= 0:
            raise TimeoutError("Camera ACK deadline exceeded")
        return value

    # Socket timeouts alone restart for each partial write/read inside websocket-client.
    watchdog = timer_factory(timeout, abort)
    watchdog.daemon = True
    watchdog.start()
    try:
        connection.settimeout(remaining())
        connection.send_binary(frame)
        connection.settimeout(remaining())
        acknowledged = connection.recv()
        remaining()
        if acknowledged != "ack":
            raise CameraAckInvalid("Unexpected camera ACK")
        return clock() - started
    except Exception:
        abort()
        raise
    finally:
        watchdog.cancel()
        # Join the single watchdog before reusing/closing this connection. No orphan timers.
        watchdog.join()

class AckVideoSender:
    """Keep only the newest frame while the one outstanding frame awaits its ACK."""
    def __init__(self, frames, settings, clock=time.monotonic, logger=print, send=send_frame_with_ack):
        self.frames, self.settings, self.clock, self.logger, self.send = frames, settings, clock, logger, send
        self.last_sequence = -1
        self.next_send_at = 0.0
        self.report_started = clock()
        self.count = self.bytes = self.oversized = 0
        self.ack_total = self.ack_max = 0.0

    def report_if_due(self):
        now = self.clock()
        elapsed = now - self.report_started
        if elapsed < 30:
            return
        average = self.ack_total / self.count if self.count else 0.0
        self.logger(f"[camera] Stream 30s: fps={self.count / elapsed:.2f}, bytes={self.bytes}, "
                    f"ack_mean_ms={average * 1000:.0f}, ack_max_ms={self.ack_max * 1000:.0f}, oversized={self.oversized}")
        self.report_started = now
        self.count = self.bytes = self.oversized = 0
        self.ack_total = self.ack_max = 0.0

    def step(self, connection):
        self.report_if_due()
        now = self.clock()
        if now < self.next_send_at:
            return False
        frame, sequence = self.frames.get(max_age=self.settings.camera_max_frame_age)
        if frame is None or sequence == self.last_sequence:
            return False
        if len(frame) > MAX_STREAM_JPEG_BYTES:
            self.last_sequence = sequence
            self.oversized += 1
            return False
        elapsed = self.send(connection, frame, self.settings.camera_ack_timeout)
        self.last_sequence = sequence
        self.next_send_at = now + 1 / self.settings.camera_cloud_fps
        self.count += 1
        self.bytes += len(frame)
        self.ack_total += elapsed
        self.ack_max = max(self.ack_max, elapsed)
        return True

def sync_directory(path):
    # POSIX directory fsync preserves rename/unlink metadata across power loss.
    if not hasattr(os, "O_DIRECTORY"):
        return
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)

def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


class CaptureQueue:
    """Persist one captured JPEG so an uncertain upload never takes a later photo."""
    def __init__(self, root, user_no, client, get_frame, clock=time.time, logger=print):
        self.root = Path(root)
        self.pending = self.root / "pending"
        self.quarantine = self.root / "quarantine"
        self.pending.mkdir(parents=True, exist_ok=True)
        self.quarantine.mkdir(parents=True, exist_ok=True)
        self.user_no, self.client, self.get_frame, self.clock, self.log = user_no, client, get_frame, clock, logger

    def save_frame(self, path, frame):
        temporary = path.with_suffix(".tmp")
        try:
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(frame)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
            sync_directory(path.parent)
        finally:
            temporary.unlink(missing_ok=True)

    def deliver(self, path):
        record = json.loads(path.read_text(encoding="utf-8"))
        frame_path = path.with_suffix(".jpg")
        if record["task"]["userNo"] != self.user_no:
            self.log("[capture] Saved event belongs to a different account; delivery paused", flush=True)
            return "retry"
        status, body = self.client.upload(record["task"], frame_path.read_bytes())
        url = (body.get("imageUrl") or body.get("image_url") or body.get("url")) if isinstance(body, dict) else None
        if status == 200 and isinstance(url, str) and url:
            path.unlink()
            frame_path.unlink(missing_ok=True)
            sync_directory(self.pending)
            return "complete"
        if status in (400, 404, 409, 410, 422):
            record["lastHttpStatus"] = status
            atomic_json(self.quarantine / path.name, record)
            os.replace(frame_path, self.quarantine / frame_path.name)
            path.unlink()
            sync_directory(self.pending)
            self.log(f"[capture] Task {record['task']['captureTaskId']} HTTP {status}; original JPEG quarantined", flush=True)
            return "quarantined"
        self.log(f"[capture] Upload HTTP {status}; same JPEG retained for retry", flush=True)
        return "retry"

    def step(self):
        for path in sorted(self.pending.glob("*.json")):
            if self.deliver(path) == "retry":
                return "retry"
        status, task = self.client.claim()
        if status == 204:
            return "idle"
        if status != 200 or not isinstance(task, dict):
            self.log(f"[capture] Claim HTTP {status}; waiting", flush=True)
            return "retry"
        for key in ("captureTaskId", "eventId", "eventDate", "expiresAt", "leaseToken"):
            if not isinstance(task.get(key), str) or not task[key]:
                raise ValueError("Capture task response is incomplete")
        if task.get("userNo") != self.user_no:
            raise ValueError("Capture task owner differs from the paired camera account")
        event_log_id(task["eventId"])
        expiry = dt.datetime.fromisoformat(task["expiresAt"].replace("Z", "+00:00"))
        if expiry.utcoffset() is None:
            raise ValueError("Capture task expiry must include a UTC offset")
        if self.clock() >= expiry.timestamp():
            self.client.failed(task, "Capture window expired; no new image taken")
            return "expired"
        frame = self.get_frame()
        if frame is None:
            self.client.failed(task, "Camera frame unavailable")
            return "unavailable"
        # Recheck after obtaining the frame; long reads must not create late photos.
        if self.clock() >= expiry.timestamp():
            self.client.failed(task, "Capture window expired; no new image taken")
            return "expired"
        path = self.pending / (task_key(task["captureTaskId"]) + ".json")
        self.save_frame(path.with_suffix(".jpg"), frame)
        atomic_json(path, {"task": task, "capturedAt": self.clock()})
        return self.deliver(path)

def task_key(task_id):
    return hashlib.sha256(str(task_id).encode("utf-8")).hexdigest()

def task_sample_directory(facedata, int_user_id, task_id):
    return Path(facedata) / str(int(int_user_id)) / ("task_" + task_key(task_id))

def prepare_model_baseline(model_path):
    """Freeze pre-migration model once, before any replay-safe training writes."""
    model_path = Path(model_path)
    model_path.parent.mkdir(parents=True, exist_ok=True)
    marker = model_path.parent / "task_model_migration.json"
    baseline = model_path.parent / "legacy_base.yml"
    if marker.exists():
        present = json.loads(marker.read_text(encoding="utf-8"))["has_legacy_model"]
        if present and not baseline.exists():
            raise RuntimeError("Legacy model baseline is missing; restore legacy_base.yml before training")
        return baseline if present else None
    if not baseline.exists() and model_path.exists():
        temporary = baseline.with_suffix(".tmp")
        with model_path.open("rb") as source, temporary.open("wb") as target:
            shutil.copyfileobj(source, target)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, baseline)
        sync_directory(baseline.parent)
    atomic_json(marker, {"has_legacy_model": baseline.exists()})
    return baseline if baseline.exists() else None

def recover_model_transaction(model_path):
    """Roll back an interrupted model/sample commit before loading recognition."""
    model_path = Path(model_path)
    journal = model_path.parent / "task_model_transaction.json"
    if not journal.exists():
        return
    record = json.loads(journal.read_text(encoding="utf-8"))
    backup = model_path.parent / "trainer.rollback.yml"
    staging = Path(record["staging"])
    destination = Path(record["destination"])
    data_root = model_path.parent.parent.resolve()
    if not staging.resolve().is_relative_to(data_root) or not destination.resolve().is_relative_to(data_root):
        raise RuntimeError("Model recovery paths are outside PI_DATA_DIR")
    if record["had_model"]:
        if not backup.exists():
            raise RuntimeError("Model rollback backup is missing; restore trainer.rollback.yml")
        # Keep the backup intact until the journal is removed, so recovery is replayable.
        temporary = model_path.with_name("trainer.recover.yml")
        shutil.copyfile(backup, temporary)
        with temporary.open("r+b") as handle:
            os.fsync(handle.fileno())
        os.replace(temporary, model_path)
    else:
        model_path.unlink(missing_ok=True)
    sync_directory(model_path.parent)
    if destination.exists() and not staging.exists():
        os.replace(destination, staging)
        sync_directory(destination.parent)
    journal.unlink()
    sync_directory(journal.parent)
    backup.unlink(missing_ok=True)
    if staging.exists():
        shutil.rmtree(staging)

def commit_model_and_samples(model_path, temporary_model, staging, destination, check_owned=lambda: None):
    """Publish both files as one recoverable transaction; exceptions restore both."""
    model_path, temporary_model = Path(model_path), Path(temporary_model)
    if staging is None:
        check_owned()
        os.replace(temporary_model, model_path)
        sync_directory(model_path.parent)
        return
    staging, destination = Path(staging), Path(destination)
    if destination.exists():
        raise RuntimeError("Task sample directory is already committed")
    recover_model_transaction(model_path)
    backup = model_path.parent / "trainer.rollback.yml"
    journal = model_path.parent / "task_model_transaction.json"
    had_model = model_path.exists()
    if had_model:
        shutil.copyfile(model_path, backup)
        with backup.open("r+b") as handle:
            os.fsync(handle.fileno())
    atomic_json(journal, {"had_model": had_model, "staging": str(staging.resolve()), "destination": str(destination.resolve())})
    try:
        check_owned()
        os.replace(temporary_model, model_path)
        sync_directory(model_path.parent)
        os.replace(staging, destination)
        sync_directory(destination.parent)
        check_owned()
        # Until this succeeds, failures roll back both the model and its samples.
        journal.unlink()
    except Exception:
        recover_model_transaction(model_path)
        raise
    # The pair is already committed. Cleanup must not turn it into a failed task.
    try:
        sync_directory(journal.parent)
    except OSError as error:
        print(f"[model] Model committed; directory sync failed, rollback backup retained: {error}", flush=True)
        return
    try:
        backup.unlink(missing_ok=True)
    except OSError as error:
        print(f"[model] Model committed; old rollback backup cleanup deferred: {error}", flush=True)

def training_image_paths(facedata):
    """Include legacy user/{index}.jpg and committed user/task_hash/*.jpg."""
    root = Path(facedata)
    if not root.exists():
        return
    for user_dir in sorted(root.iterdir()):
        if not user_dir.is_dir() or user_dir.is_symlink() or not user_dir.name.isdigit():
            continue
        for path in sorted(user_dir.rglob("*")):
            relative = path.relative_to(user_dir)
            if any(part.startswith(".") for part in relative.parts):
                continue
            if path.is_file() and not path.is_symlink() and path.suffix.lower() in (".jpg", ".jpeg", ".png"):
                yield int(user_dir.name), path

class TaskState:
    def __init__(self, root):
        self.root = Path(root)
        self.outbox = self.root / "outbox"
        self.receipts = self.root / "receipts"
        self.quarantine = self.root / "quarantine"
        for directory in (self.outbox, self.receipts, self.quarantine):
            directory.mkdir(parents=True, exist_ok=True)

    def completed_result(self, task_id):
        path = self.receipts / (task_key(task_id) + ".json")
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))["result"]
        return None

    def run_once(self, task_id, operation):
        result = self.completed_result(task_id)
        if result is not None:
            return result
        result = operation()
        if result.get("status") == "success":
            try:
                atomic_json(self.receipts / (task_key(task_id) + ".json"), {"task_id": str(task_id), "result": result})
            except OSError as error:
                # Training has committed already; report its real outcome. If the
                # receipt is unavailable on replay, the task-keyed dataset is rebuilt.
                print(f"[task] Task {task_id} completed; receipt could not be saved, replay will rebuild without duplicate samples: {error}", flush=True)
        return result

    def queue(self, payload):
        key = task_key(str(payload["task_id"]) + ":" + payload["lease_token"])
        path = self.outbox / (key + ".json")
        atomic_json(path, payload)
        return path

    def pending(self):
        return sorted(self.outbox.glob("*.json"))

    def read(self, path):
        return json.loads(Path(path).read_text(encoding="utf-8"))

    def deliver(self, path, post_result):
        """Delete only after durable ACK; retain errors; isolate a stale lease."""
        payload = self.read(path)
        status, body = post_result(payload)
        if status == 200 and isinstance(body, dict) and body.get("status") == "success":
            Path(path).unlink()
            sync_directory(Path(path).parent)
            return "acknowledged"
        if status in (400, 404, 409, 410, 422):
            os.replace(path, self.quarantine / Path(path).name)
            sync_directory(self.quarantine)
            sync_directory(self.outbox)
            return "stale" if status == 409 else f"permanent:{status}"
        if status == 403:
            return "forbidden"
        return "retry"
