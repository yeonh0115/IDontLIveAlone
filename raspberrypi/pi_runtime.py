"""Hardware-independent configuration and durable task state for the door Pi."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import threading
import time
from urllib.parse import urljoin, urlparse
import uuid

DEFAULT_SERVER = "https://idontlivealone.onrender.com"

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

    def get(self):
        with self.lock:
            if self.frame is None or self.clock() - self.updated_at > self.max_age:
                return None, self.sequence
            return self.frame, self.sequence

    def clear(self):
        with self.lock:
            self.frame = None

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
        with temporary.open("w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)

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
            atomic_json(self.receipts / (task_key(task_id) + ".json"), {"task_id": str(task_id), "result": result})
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
