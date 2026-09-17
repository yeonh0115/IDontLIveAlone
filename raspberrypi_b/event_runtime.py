"""UART event mapping and persistent HTTPS delivery state; no hardware imports."""
import datetime as dt
import json
import os
from pathlib import Path
import threading
import time
from urllib.parse import urlsplit
import uuid

SPECS = {
    "SENSOR_MOVE": (1, "SENSOR", "움직임 센서", 6.0, "medium", "현관 밖 지속적인 움직임 감지"),
    "SECURITY_HIGH": (4, "SECURITY", "현관 도어락 보디가드", 2.0, "high", "지속적이고 강력한 충격 발생!"),
    "SECURITY_MIDDLE": (3, "SECURITY", "현관 도어락 보디가드", 1.0, "high", "비정상적인 외부 충격 감지"),
    "SECURITY_LOW": (2, "SECURITY", "현관 도어락 보디가드", 0.5, "low", "현관문 근처 가벼운 진동 감지"),
}

class Config:
    def __init__(self, env=None, source_dir=None):
        env = os.environ if env is None else env
        self.server_url = env.get("RENDER_SERVER_URL", "https://idontlivealone.onrender.com").rstrip("/")
        self._validate_url(self.server_url, https_only=True)
        value = env.get("USER_NO", "").strip()
        self.pairing_enabled = env.get("DEVICE_PAIRING", "0" if value or env.get("DEVICE_API_TOKEN") else "1").strip() != "0"
        self.device = None
        self.event_photos_enabled = env.get("EVENT_PHOTOS_ENABLED", "false").strip().lower() in ("1", "true", "yes")
        if not self.pairing_enabled and (not value.isascii() or not value.isdigit() or int(value) <= 0):
            raise ValueError("USER_NO must be the account's positive numeric user number")
        self.user_no = None if self.pairing_enabled else int(value)
        self.token = env.get("DEVICE_API_TOKEN", "").strip()
        if not self.pairing_enabled and (len(self.token) < 32 or any(char.isspace() for char in self.token)):
            raise ValueError("DEVICE_API_TOKEN requires at least 32 characters without whitespace")
        self.camera_url = env.get("CAMERA_TRIGGER_URL", "").strip() or None
        if self.camera_url:
            self._validate_url(self.camera_url)
        source = Path(source_dir or Path(__file__).resolve().parent)
        self.source_dir = source
        root = Path(env.get("PI_B_DATA_DIR", str(source / "event_state")))
        self.data_dir = root if root.is_absolute() else source / root
        self.uart_port = env.get("UART_PORT", "/dev/serial0")
        self.baud_rate = int(env.get("UART_BAUD_RATE", "115200"))
        self.photo_max_delay = float(env.get("PHOTO_MAX_DELAY_SECONDS", "120"))
        if self.baud_rate <= 0 or not 0 <= self.photo_max_delay <= 3600:
            raise ValueError("UART_BAUD_RATE must be positive and PHOTO_MAX_DELAY_SECONDS must be 0-3600")

    def activate_pairing(self):
        if not self.pairing_enabled:
            return
        import sys
        sys.path.insert(0, str(self.source_dir.parent / "device_client"))
        from device_pairing import device_from_environment
        self.device = device_from_environment("SENSOR", self.source_dir)
        self.device.wait_paired()
        self.user_no, self.token = self.device.user_no, self.device.token

    @staticmethod
    def _validate_url(value, https_only=False):
        parsed = urlsplit(value)
        allowed = ("https",) if https_only else ("http", "https")
        if parsed.scheme not in allowed or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("Endpoint must use the expected HTTP(S) scheme without embedded credentials")

def event_key(line):
    # Keep the STM32 protocol and original matching order unchanged.
    return next((key for key in SPECS if key in line), None)

class EventGate:
    def __init__(self):
        self.level = 0
        self.accepted_at = float("-inf")

    def accepts(self, key, now):
        level = 0 if now - self.accepted_at > 10 else self.level
        return SPECS[key][0] > level or now - self.accepted_at >= 3

    def mark(self, key, now):
        self.level, self.accepted_at = SPECS[key][0], now

def make_event(key, user_no, occurred_at=None, event_id=None):
    occurred_at = occurred_at or dt.datetime.now().astimezone()
    if occurred_at.tzinfo is None or occurred_at.utcoffset() is None:
        raise ValueError("Event time requires a UTC offset")
    _, log_type, sub_type, value, severity, description = SPECS[key]
    return {
        "eventId": event_id or str(uuid.uuid4()), "userNo": user_no,
        "occurredAt": occurred_at.isoformat(timespec="milliseconds"),
        "logType": log_type, "subType": sub_type, "val1": value, "val2": 0.0,
        "severity": severity, "description": description,
    }

def sync_directory(path):
    if hasattr(os, "O_DIRECTORY"):
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

class EventOutbox:
    def __init__(self, root):
        self.root = Path(root)
        self.pending = self.root / "pending"
        self.completed = self.root / "completed"
        self.quarantine = self.root / "quarantine"
        for directory in (self.pending, self.completed, self.quarantine):
            directory.mkdir(parents=True, exist_ok=True)

    def enqueue(self, payload, photo_max_delay):
        identity = str(uuid.UUID(payload["eventId"]))
        path = self.pending / (identity + ".json")
        occurred = dt.datetime.fromisoformat(payload["occurredAt"]).timestamp()
        atomic_json(path, {"version": 1, "event": payload, "phase": "event", "photoDeadline": occurred + photo_max_delay})
        return path

    def read(self, path):
        return json.loads(Path(path).read_text(encoding="utf-8"))

    def save(self, path, record):
        atomic_json(path, record)

    def finish(self, path, record, quarantined=False):
        destination = (self.quarantine if quarantined else self.completed) / Path(path).name
        atomic_json(destination, record)
        Path(path).unlink(missing_ok=True)
        sync_directory(self.pending)

    def paths(self):
        return sorted(self.pending.glob("*.json"), key=lambda path: path.stat().st_mtime)

class DeliveryWorker:
    def __init__(self, config, outbox, client, clock=time.time, logger=print):
        self.config, self.outbox, self.client = config, outbox, client
        self.clock, self.log = clock, logger

    def remember_error(self, path, record, error):
        record["lastError"] = error
        self.outbox.save(path, record)
        self.log(f"[event {record['event']['eventId']}] {error}", flush=True)

    def process(self, path):
        record = self.outbox.read(path)
        payload = record["event"]
        identity = payload["eventId"]
        if payload["userNo"] != self.config.user_no:
            self.remember_error(path, record, "Saved event belongs to another account; delivery paused for configuration review")
            return "retry"
        if record["phase"] == "event":
            status, body = self.client.register(payload)
            acknowledged = (
                status == 200 and isinstance(body, dict) and body.get("success") is True
                and body.get("eventId") == identity and body.get("userNo") == payload["userNo"]
                and isinstance(body.get("logId"), int) and not isinstance(body.get("logId"), bool)
                and body["logId"] > 0
            )
            if acknowledged and self.config.pairing_enabled:
                acknowledged = (
                    isinstance(body.get("cameraCaptureQueued"), bool)
                    and body.get("captureStatus") in ("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "EXPIRED", "NOT_REQUESTED")
                    and (not body["cameraCaptureQueued"] or isinstance(body.get("captureTaskId"), str))
                )
            if not acknowledged:
                error = f"Event API HTTP {status}; event retained, photo not requested"
                if status in (400, 409, 422):
                    record["lastError"] = error
                    self.outbox.finish(path, record, quarantined=True)
                    self.log(f"[event {identity}] {error}; quarantined", flush=True)
                    return "quarantined"
                if status in (401, 403):
                    error += "; check DEVICE_API_TOKEN / USER_NO binding"
                self.remember_error(path, record, error)
                return "retry"
            record["serverAck"] = {key: body.get(key) for key in ("eventId", "logId", "userNo", "eventDate", "duplicate", "cameraCaptureQueued", "captureTaskId", "captureStatus")}
            record["phase"] = "photo"
            record.pop("lastError", None)
            self.outbox.save(path, record)  # Persist the ACK before contacting the camera.
            if self.config.pairing_enabled:
                # The server reserves capture atomically with the event. This is an
                # acknowledged reservation, never a claim that a photograph exists.
                record["photoStatus"] = "server_managed"
                record["captureTaskId"] = body.get("captureTaskId")
                record["captureStatus"] = body.get("captureStatus")
                self.outbox.finish(path, record)
                return "complete"
        if self.config.pairing_enabled:
            # A crash after the event ACK must still avoid a legacy LAN request.
            if not isinstance(record.get("serverAck", {}).get("cameraCaptureQueued"), bool):
                record["phase"] = "event"
                self.outbox.save(path, record)
                return "retry"
            record["photoStatus"] = "server_managed"
            self.outbox.finish(path, record)
            return "complete"
        if not self.config.event_photos_enabled:
            record["photoStatus"] = "disabled"
            self.outbox.finish(path, record)
            return "complete"
        if self.clock() >= record["photoDeadline"]:
            record["photoStatus"] = "expired"
            self.outbox.finish(path, record)
            self.log(f"[event {identity}] Event registered; photo window expired, no new photo captured", flush=True)
            return "expired"
        if not self.config.camera_url:
            self.remember_error(path, record, "CAMERA_TRIGGER_URL missing; registered event retained for photo retry")
            return "retry"
        status, body = self.client.capture(identity, payload["userNo"])
        success = (
            status == 200 and isinstance(body, dict) and body.get("status") == "success"
            and body.get("log_id") == identity and body.get("user_no") == payload["userNo"]
            and isinstance(body.get("image_url"), str) and bool(body["image_url"])
        )
        if success:
            record["photoStatus"] = "success"
            record["imageUrl"] = body["image_url"]
            record["capturedResponseAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
            self.outbox.finish(path, record)
            return "complete"
        if status == 410:
            record["photoStatus"] = "disabled"
            self.outbox.finish(path, record)
            return "complete"
        error = f"Camera HTTP {status}; event already registered, photo not confirmed"
        if status == 403:
            error += "; Pi A USER_NO does not match expected_user_no, check both devices"
        self.remember_error(path, record, error)
        return "retry"

    def run(self, stop):
        refreshed = 0.0
        while not stop.is_set():
            if self.config.device and time.monotonic() >= refreshed:
                try:
                    self.config.device.refresh()
                    if self.config.device.user_no != self.config.user_no:
                        raise RuntimeError("Sensor pairing/owner changed")
                    refreshed = time.monotonic() + 30
                except Exception as error:
                    self.log(f"[pairing] {type(error).__name__}; event delivery paused, outbox retained", flush=True)
                    stop.wait(5)
                    continue
            for path in self.outbox.paths():
                if stop.is_set():
                    break
                try:
                    self.process(path)
                except Exception as error:
                    # Network exception text can contain proxy credentials; emit only its class.
                    self.log(f"[delivery] {type(error).__name__}; durable event retained", flush=True)
            stop.wait(2)
