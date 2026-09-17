"""Local face recognition and leased Render training jobs for one door Pi."""
import hashlib
import os
from pathlib import Path
import shutil
import threading
import time
import uuid

import cv2
import numpy as np
import requests

from pi_runtime import (
    LatestFrame, Settings, TaskState, commit_model_and_samples, prepare_model_baseline,
    recover_model_transaction, sync_directory, task_sample_directory, training_image_paths,
)

settings = Settings()
MODEL_PATH = settings.data_dir / "trainer" / "trainer.yml"
MAP_FILE_PATH = settings.data_dir / "trainer" / "user_map.txt"
FACEDATA_DIR = settings.data_dir / "facedata"
THRESHOLD = settings.face_threshold
REQUIRED_CONSECUTIVE_SUCCESS = settings.face_required_successes
SOLENOID_PIN = settings.solenoid_pin

frames = LatestFrame()
stop_event = threading.Event()
model_lock = threading.Lock()
cascade_lock = threading.Lock()
gpio_busy = threading.Event()
recognizer = None
face_cascade = None
gpio_request = None
gpio_module = None

def get_or_create_int_id(string_user_id):
    string_user_id = str(string_user_id)
    if not string_user_id or any(char in string_user_id for char in "=\r\n"):
        raise ValueError("Invalid user_id")
    mapping = read_user_mapping()
    if string_user_id in mapping:
        return mapping[string_user_id]
    label = int(hashlib.sha256(string_user_id.encode("utf-8")).hexdigest(), 16) % 100000000 + 1
    occupied = set(mapping.values())
    while label in occupied:
        label = label % 100000000 + 1
    mapping[string_user_id] = label
    MAP_FILE_PATH.parent.mkdir(parents=True, exist_ok=True)
    temporary = MAP_FILE_PATH.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        handle.writelines(f"{key}={value}\n" for key, value in mapping.items())
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, MAP_FILE_PATH)
    sync_directory(MAP_FILE_PATH.parent)
    return label

def read_user_mapping():
    mapping = {}
    if MAP_FILE_PATH.exists():
        for line in MAP_FILE_PATH.read_text(encoding="utf-8").splitlines():
            user_id, separator, label = line.partition("=")
            if separator and label.isdigit():
                mapping[user_id] = int(label)
    return mapping

def get_string_user_id(label):
    for user_id, stored_label in read_user_mapping().items():
        if stored_label == int(label):
            return user_id
    return "Unknown"

def initialize():
    global recognizer, face_cascade, gpio_request, gpio_module
    FACEDATA_DIR.mkdir(parents=True, exist_ok=True)
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    recover_model_transaction(MODEL_PATH)
    face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    if face_cascade.empty():
        raise RuntimeError("OpenCV Haar face detector could not be loaded")
    if MODEL_PATH.exists():
        try:
            recognizer = cv2.face.LBPHFaceRecognizer_create()
            recognizer.read(str(MODEL_PATH))
        except Exception as error:
            recognizer = None
            print(f"[face] Existing model could not be loaded: {error}", flush=True)
    try:
        import gpiod
        gpio_module = gpiod
        with gpiod.Chip("/dev/gpiochip0") as chip:
            config = gpiod.LineSettings(direction=gpiod.line.Direction.OUTPUT, output_value=gpiod.line.Value.INACTIVE)
            gpio_request = chip.request_lines(config={SOLENOID_PIN: config})
    except Exception as error:
        gpio_request = None
        print(f"[face] GPIO unavailable; recognition only: {error}", flush=True)

def trigger_face_signal(user_id):
    try:
        if gpio_request is not None:
            gpio_request.set_values({SOLENOID_PIN: gpio_module.line.Value.ACTIVE})
        print(f"[face] Recognized {user_id}; GPIO {SOLENOID_PIN} active for 3 seconds", flush=True)
        stop_event.wait(3)
    finally:
        if gpio_request is not None:
            try:
                gpio_request.set_values({SOLENOID_PIN: gpio_module.line.Value.INACTIVE})
            except Exception as error:
                print(f"[face] GPIO reset failed: {error}", flush=True)
        gpio_busy.clear()

def frame_reader():
    capture = None
    failures = 0
    try:
        while not stop_event.is_set():
            if capture is None:
                capture = cv2.VideoCapture(settings.stream_url)
            success, frame = capture.read()
            if success:
                frames.put(frame)
                failures = 0
                continue
            failures += 1
            if failures > 30:
                frames.clear()
                capture.release()
                capture = None
                failures = 0
                print("[face] Camera stream unavailable; reconnecting", flush=True)
            stop_event.wait(0.1)
    finally:
        frames.clear()
        if capture is not None:
            capture.release()

def detect_faces(gray, scale_factor=1.1, min_size=(40, 40)):
    with cascade_lock:
        return face_cascade.detectMultiScale(gray, scaleFactor=scale_factor, minNeighbors=5, minSize=min_size)

def normalize_face(gray, face):
    x, y, width, height = face
    return cv2.equalizeHist(cv2.resize(gray[y:y + height, x:x + width], (200, 200)))

def predict_frame(frame, reduce_size=False):
    if reduce_size:
        frame = cv2.resize(frame, (0, 0), fx=0.5, fy=0.5)
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    faces = detect_faces(gray)
    if len(faces) == 0:
        return {"status": "error", "message": "No face detected"}
    with model_lock:
        if recognizer is None:
            return {"status": "error", "message": "No trained model"}
        for face in sorted(faces, key=lambda item: item[2] * item[3], reverse=True):
            label, confidence = recognizer.predict(normalize_face(gray, face))
            user_id = get_string_user_id(label)
            if user_id != "Unknown" and confidence < THRESHOLD:
                return {"status": "success", "message": "Registered face recognized", "detected_id": user_id, "confidence": round(float(confidence), 2)}
    return {"status": "error", "message": "Unregistered face"}

def real_time_recognition_loop():
    last_sequence = -1
    last_id = None
    consecutive = 0
    while not stop_event.is_set():
        frame, sequence = frames.get()
        if frame is None:
            last_id, consecutive = None, 0
            stop_event.wait(0.01)
            continue
        if sequence == last_sequence:
            stop_event.wait(0.01)
            continue
        last_sequence = sequence
        try:
            result = predict_frame(frame, reduce_size=True)
            user_id = result.get("detected_id") if result["status"] == "success" else None
            consecutive = consecutive + 1 if user_id is not None and user_id == last_id else (1 if user_id else 0)
            last_id = user_id
            if consecutive >= REQUIRED_CONSECUTIVE_SUCCESS:
                if not gpio_busy.is_set():
                    gpio_busy.set()
                    threading.Thread(target=trigger_face_signal, args=(user_id,), daemon=True).start()
                consecutive = 0
        except Exception as error:
            last_id, consecutive = None, 0
            print(f"[face] Recognition failed: {error}", flush=True)
            stop_event.wait(1)

class LeaseHeartbeat:
    def __init__(self, task, claimed_at=None, clock=time.monotonic):
        self.task = task
        self.clock = clock
        self.deadline = (clock() if claimed_at is None else claimed_at) + 120
        self.deadline_lock = threading.Lock()
        self.stopped = threading.Event()
        self.lost = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stopped.set()
        self.thread.join(timeout=7)

    def assert_owned(self):
        with self.deadline_lock:
            expired = self.clock() >= self.deadline
        if self.lost.is_set() or expired:
            raise RuntimeError("Task lease was superseded; processing stopped")

    def _run(self):
        with requests.Session() as heartbeat_session:
            while not self.stopped.wait(30):
                try:
                    request_started = self.clock()
                    response = heartbeat_session.post(
                        settings.api_url(f"/api/face/tasks/{self.task['task_id']}/lease"),
                        json={"device_id": settings.device_id, "lease_token": self.task["lease_token"]},
                        headers=settings.auth_headers(),
                        timeout=5,
                    )
                    if response.status_code in (401, 403, 409):
                        self.lost.set()
                        return
                    if response.status_code == 200:
                        with self.deadline_lock:
                            self.deadline = request_started + 120
                    else:
                        print(f"[task] Lease renewal returned HTTP {response.status_code}", flush=True)
                except requests.RequestException as error:
                    print(f"[task] Lease renewal unavailable: {error}", flush=True)

def process_train(task, session, lease):
    """Rebuild baseline + unique task samples, even after interrupted acknowledgements."""
    global recognizer
    task_id = task["task_id"]
    user_id = task.get("user_id")
    if not user_id:
        raise ValueError("TRAIN requires user_id")
    recover_model_transaction(MODEL_PATH)
    lease.assert_owned()
    baseline = prepare_model_baseline(MODEL_PATH)
    int_user_id = get_or_create_int_id(user_id)
    destination = task_sample_directory(FACEDATA_DIR, int_user_id, task_id)
    staging = None
    if not destination.exists():
        destination.parent.mkdir(parents=True, exist_ok=True)
        staging = destination.parent / (".pending_" + uuid.uuid4().hex)
        staging.mkdir()
        try:
            urls = task.get("image_urls", [])
            if not isinstance(urls, list) or not urls:
                raise ValueError("TRAIN requires image_urls")
            samples_saved = 0
            for index, url in enumerate(urls):
                lease.assert_owned()
                image_url = settings.api_url(url)
                if not image_url.startswith(settings.server_url + "/"):
                    raise ValueError("Training images must come from the configured server")
                response = session.get(image_url, headers=settings.auth_headers(), timeout=10)
                response.raise_for_status()
                image = cv2.imdecode(np.frombuffer(response.content, np.uint8), cv2.IMREAD_COLOR)
                if image is None:
                    raise ValueError(f"Training image {index} could not be decoded")
                gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
                detected = detect_faces(gray, scale_factor=1.3, min_size=(20, 20))
                if len(detected) == 0:
                    raise ValueError(f"Training image {index} contains no detectable face")
                largest = max(detected, key=lambda item: item[2] * item[3])
                normalized = normalize_face(gray, largest)
                if not cv2.imwrite(str(staging / f"{index}.jpg"), normalized):
                    raise OSError("Could not persist training image")
                with (staging / f"{index}.jpg").open("r+b") as handle:
                    os.fsync(handle.fileno())
                samples_saved += 1
            if samples_saved == 0:
                raise ValueError("No usable face samples")
            lease.assert_owned()
            sync_directory(staging)
        except Exception:
            if staging.exists():
                shutil.rmtree(staging)
            raise
    images, labels = [], []
    for label, path in training_image_paths(FACEDATA_DIR):
        # Existing model is the authoritative baseline; avoid adding legacy files twice.
        if baseline is not None and not path.relative_to(FACEDATA_DIR / str(label)).parts[0].startswith("task_"):
            continue
        gray = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if gray is None:
            raise ValueError(f"Stored training image could not be decoded: {path.name}")
        images.append(cv2.equalizeHist(cv2.resize(gray, (200, 200))))
        labels.append(label)
    if staging is not None:
        for path in sorted(staging.glob("*.jpg")):
            gray = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
            if gray is None:
                raise ValueError(f"Pending training image could not be decoded: {path.name}")
            images.append(cv2.equalizeHist(cv2.resize(gray, (200, 200))))
            labels.append(int_user_id)
    if not images:
        raise ValueError("No saved face samples are available")
    new_recognizer = cv2.face.LBPHFaceRecognizer_create()
    if baseline is not None:
        new_recognizer.read(str(baseline))
        new_recognizer.update(images, np.array(labels, dtype=np.int32))
    else:
        new_recognizer.train(images, np.array(labels, dtype=np.int32))
    temporary = MODEL_PATH.with_name("trainer." + uuid.uuid4().hex + ".yml")
    try:
        new_recognizer.write(str(temporary))
        with temporary.open("r+b") as handle:
            os.fsync(handle.fileno())
        lease.assert_owned()
        with model_lock:
            lease.assert_owned()
            commit_model_and_samples(MODEL_PATH, temporary, staging, destination, lease.assert_owned)
            recognizer = new_recognizer
    finally:
        temporary.unlink(missing_ok=True)
        if staging is not None and staging.exists():
            shutil.rmtree(staging)
    return {"status": "success", "message": f"User {user_id} trained; {len(images)} task/legacy samples rebuilt"}

def watch_render_server():
    state = TaskState(settings.data_dir / "task_state")
    with requests.Session() as session:
        def post_result(payload):
            response = session.post(settings.api_url("/api/result"), json=payload, headers=settings.auth_headers(), timeout=5)
            try:
                body = response.json()
            except ValueError:
                body = None
            return response.status_code, body

        def log_delivery(task, delivery):
            if delivery == "stale":
                print(f"[task] HTTP 409: stale lease result quarantined for task {task['task_id']}", flush=True)
            elif delivery.startswith("permanent:"):
                status = delivery.split(":", 1)[1]
                print(f"[task] HTTP {status}: permanently rejected result quarantined unchanged for task {task['task_id']}", flush=True)
            elif delivery == "forbidden":
                print(f"[task] HTTP 403 for task {task['task_id']}: check DEVICE_ID/server permissions; saved result retained, polling paused", flush=True)

        def flush_pending():
            for path in state.pending():
                task = state.read(path)
                with LeaseHeartbeat(task):
                    while not stop_event.is_set():
                        try:
                            delivery = state.deliver(path, post_result)
                            log_delivery(task, delivery)
                            if delivery not in ("retry", "forbidden"):
                                break
                            if delivery == "retry":
                                print(f"[task] Result not acknowledged for {task['task_id']}; retrying", flush=True)
                        except requests.RequestException as error:
                            print(f"[task] Saved result delivery unavailable: {error}", flush=True)
                        stop_event.wait(3)
                    else:
                        return False
            return True

        while not stop_event.is_set():
            try:
                if not flush_pending():
                    stop_event.wait(3)
                    continue
                claim_started = time.monotonic()
                response = session.get(settings.api_url("/api/get-task"), params=settings.task_params(), headers=settings.auth_headers(), timeout=5)
                if response.status_code == 204:
                    stop_event.wait(1)
                    continue
                response.raise_for_status()
                task = response.json()
                if not task.get("task_id") or not task.get("lease_token"):
                    raise ValueError("Task response is missing task_id or lease_token")
                task_type = task.get("type")
                with LeaseHeartbeat(task, claimed_at=claim_started) as lease:
                    try:
                        if task_type == "TRAIN":
                            result = state.run_once(task["task_id"], lambda: process_train(task, session, lease))
                        elif task_type == "PREDICT":
                            frame, _ = frames.get()
                            result = predict_frame(frame) if frame is not None else {"status": "error", "message": "Camera frame unavailable"}
                        else:
                            result = {"status": "error", "message": "Unsupported task type"}
                    except Exception as error:
                        result = {"status": "error", "message": str(error)}
                    payload = {
                        "task_id": str(task["task_id"]), "type": task_type,
                        "device_id": settings.device_id, "lease_token": task["lease_token"], "result": result,
                    }
                    path = state.queue(payload)
                    delivery = state.deliver(path, post_result)
                    log_delivery(task, delivery)
            except Exception as error:
                print(f"[task] Waiting to retry: {error}", flush=True)
                stop_event.wait(3)
            stop_event.wait(1)

def start_cloud_tasks():
    settings.activate_pairing()
    watch_render_server()


if __name__ == "__main__":
    initialize()
    threading.Thread(target=frame_reader, daemon=True).start()
    threading.Thread(target=start_cloud_tasks, daemon=True).start()
    try:
        real_time_recognition_loop()
    except KeyboardInterrupt:
        pass
    finally:
        stop_event.set()
        if gpio_request is not None:
            try:
                gpio_request.set_values({SOLENOID_PIN: gpio_module.line.Value.INACTIVE})
            finally:
                gpio_request.release()
