#!/usr/bin/env python3
"""나 혼자 안 산다: 기존 카메라를 공유하는 독립 칼 감지 프로그램.

기존 fin_camera.py/얼굴 모델/장치 연결 파일을 수정하지 않습니다.
best.pt(knife 클래스)를 이 파일 옆에 둡니다. 사진/영상을 저장하지 않습니다.

  python3 weapon_detection.py --self-test       # 카메라/DB 접근 없는 자체 검증
  python3 weapon_detection.py --check-model     # 실제 모델 로드/빈 영상 추론
  python3 weapon_detection.py                  # 감지만 실행, 서버 전송 안 함
  python3 weapon_detection.py --print-service  # 부팅 서비스 내용만 출력
  sudo python3 weapon_detection.py --install  # 승인된 전용 환경/서비스 신규 설치
  python3 weapon_detection.py --sd-bootstrap  # 승인 후 준비한 SD의 1회 설치 전용

실제 전송은 --send-events가 있어야 합니다. CAMERA의 SECURITY/흉기감지/high
전송을 허용한 서버 버전(64386c1 이후) 배포 후에 사용합니다.
자동 실행 서비스 추가 및 별도 Python 환경 설치는 사용자 승인 후 진행합니다.

2초 이상 연속 양성인 관측이 있어야 기록합니다. 음성 관측이나 관측 공백은
확정 전 타이머를 초기화합니다. 확정 후에는 1초 이상 음성 관측을 확인해야
다시 기록할 수 있습니다. 카메라 단절 자체를 '흉기 사라짐'으로 판단하지 않습니다.
val1=연속 감지 중 최고 신뢰도(0~1), val2=연속 감지 시간(초).
전송 시에만 AI 전용 SQLite 상태 파일을 생성합니다. 재부팅/통신 오류에도
동일 eventId와 내용을 재전송하며, 기존 장치의 credentials는 읽기만 합니다.
"""

import argparse
from collections import deque
from contextlib import contextmanager
from dataclasses import dataclass
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlsplit
import uuid

PROJECT = Path('/home/yeonh0115/cctvStreaming')
MODEL_SHA256 = '172c8b372cb00553eac125348b581ec867e836d18f775cf1db393ce813193382'
MAX_JPEG = 1_000_000
SD_BOOT_OPTIONS = (
    'systemd.run=/boot/firmware/weapon-ai/weapon-bootstrap.sh',
    'systemd.run_success_action=reboot',
    'systemd.run_failure_action=poweroff',
    'systemd.unit=kernel-command-line.target',
)
# The release preparation step fills this reviewed, exact ARM64 wheel allowlist.
WHEEL_HASHES = {
    "anyio-4.15.1-py3-none-any.whl": "6152fdbbf9a77fdec97731721bebf7c4c44f7c29b424b0065826173efc7ed101",
    "certifi-2026.7.22-py3-none-any.whl": "62f22742b58a1a33014a2b6b706588a8d7e2a88ae7bd1a6ebe8c992928483775",
    "charset_normalizer-3.5.2-cp313-cp313-manylinux2014_aarch64.manylinux_2_17_aarch64.manylinux_2_28_aarch64.whl": "849df64e889b2e17230d58410a03dba311a65b163508fd33679b2b737d4b7858",
    "cloudpickle-3.1.2-py3-none-any.whl": "9acb47f6afd73f60dc1df93bb801b472f05ff42fa6c84167d25cb206be1fbf4a",
    "contourpy-1.4.0-cp313-cp313-manylinux_2_26_aarch64.manylinux_2_28_aarch64.whl": "9c0e07c691f3b3321913ed9b8161c50ea5f77fadd006f52f5e755359b0dcbfc5",
    "cycler-0.12.1-py3-none-any.whl": "85cef7cff222d8644161529808465972e51340599459b8ac3ccbac5a854e0d30",
    "filelock-4.0.8-py3-none-any.whl": "325ff22f358c18443b1fcdfa0a7aa3faec4b500c2807c554719da4567b533d31",
    "fonttools-4.66.1-cp313-cp313-manylinux2014_aarch64.manylinux_2_17_aarch64.manylinux_2_28_aarch64.whl": "1801fdad5600118327171e0e8aa79f7cc48831dd55ab36998c9de03bd5ffe6cd",
    "fsspec-2026.9.0-py3-none-any.whl": "8dd6e646e99ea382bd85f97a45e6b526a442d79423a7dc673f1e2756d05fcb5f",
    "h11-0.16.0-py3-none-any.whl": "63cf8bbe7522de3bf65932fda1d9c2772064ffb3dae62d55932da54b31cb6c86",
    "httpcore-1.0.9-py3-none-any.whl": "2d400746a40668fc9dec9810239072b40b4484b640a8c38fd654a024c7a1bf55",
    "httpx-0.28.1-py3-none-any.whl": "d909fcccc110f8c7faf814ca82a9a4d816bc5a6dbfea25d6591d6985b8ba59ad",
    "idna-3.20-py3-none-any.whl": "ab7ae7122974553370f0bdb919e1a960b2cd1bc1ef0276416d896db81c14582c",
    "jinja2-3.1.6-py3-none-any.whl": "85ece4451f492d0c13c5dd7c13a64681a86afae63a5f347908daf103ce6d2f67",
    "kiwisolver-1.5.1-cp313-cp313-manylinux_2_24_aarch64.manylinux_2_28_aarch64.whl": "21e46b23a2da695c364124817bc01d970effd5483147f8d66a6a7167e3f6b851",
    "markupsafe-3.0.3-cp313-cp313-manylinux2014_aarch64.manylinux_2_17_aarch64.manylinux_2_28_aarch64.whl": "133a43e73a802c5562be9bbcd03d090aa5a1fe899db609c29e8c8d815c5f6de6",
    "matplotlib-3.11.2-cp313-cp313-manylinux_2_26_aarch64.manylinux_2_28_aarch64.whl": "af2661f6ac6bbd1d081996f54fdd9385715c625ace8cec0869055c0cfbf38981",
    "mpmath-1.3.0-py3-none-any.whl": "a0b2b9fe80bbcd81a6647ff13108738cfb482d481d826cc0e02f5b35e5c88d2c",
    "networkx-3.7-py3-none-any.whl": "e3fd2c13a7814cee3746340d8d7f8598a67f16a58bf47fb7f8793fab6efca1b0",
    "numpy-2.3.5-cp313-cp313-manylinux_2_27_aarch64.manylinux_2_28_aarch64.whl": "9c75442b2209b8470d6d5d8b1c25714270686f14c749028d2199c54e29f20b4d",
    "nvidia_ml_py-13.615.71-py3-none-any.whl": "959bf4adf6fe1308e4bd739e722236b0d1ec8392e2cefad33ff70c311380b9b6",
    "opencv_python-5.0.0.93-cp37-abi3-manylinux2014_aarch64.manylinux_2_17_aarch64.whl": "e2b4272e736836f66c2d176e43ab8101f3a00d45654916399f52e150c58981ac",
    "packaging-26.3-py3-none-any.whl": "d7193f7c8e4e93f444fde0262bf90af30e16fa0ad0ad44cb553c87339b23cd1c",
    "pillow-12.3.0-cp313-cp313-manylinux_2_27_aarch64.manylinux_2_28_aarch64.whl": "f7401aebd7f581d7f83a439d87d474999317ee099218e5ad25d125290990ba65",
    "polars-1.44.2-py3-none-any.whl": "1bb331f17a40d9d931101533dcd33637b66edc61eb377b07020dac16a0f0377b",
    "polars_runtime_32-1.44.2-cp310-abi3-manylinux_2_17_aarch64.manylinux2014_aarch64.whl": "bbf9b45040291dc1c6c588c837019c33557bde25ec536562a9cca9e1f6dfcc45",
    "psutil-7.2.2-cp36-abi3-manylinux2014_aarch64.manylinux_2_17_aarch64.manylinux_2_28_aarch64.whl": "b0726cecd84f9474419d67252add4ac0cd9811b04d61123054b9fb6f57df6e9e",
    "pyparsing-3.3.3-py3-none-any.whl": "ece8c00a69cf01b45d0b1dedabb469c90d8caf996d4fda40f147627a122849a4",
    "python_dateutil-2.9.0.post0-py2.py3-none-any.whl": "a8b2bc7bffae282281c8140a97d3aa9c14da0b136dfe83f850eea9a5f7470427",
    "pyyaml-6.0.3-cp313-cp313-manylinux2014_aarch64.manylinux_2_17_aarch64.manylinux_2_28_aarch64.whl": "ee2922902c45ae8ccada2c5b501ab86c36525b883eff4255313a253a3160861c",
    "requests-2.34.2-py3-none-any.whl": "2a0d60c172f83ac6ab31e4554906c0f3b3588d37b5cb939b1c061f4907e278e0",
    "setuptools-84.0.0-py3-none-any.whl": "51a52592b3b99e102b609654876bd65f19f999935166d1352678931132b0c670",
    "six-1.17.0-py2.py3-none-any.whl": "4721f391ed90541fddacab5acf947aa0d3dc7d27b2e1e8eda2be8970586c3274",
    "sympy-1.14.0-py3-none-any.whl": "e091cc3e99d2141a0ba2847328f5479b05d94a6635cb96148ccb3f34671bd8f5",
    "torch-2.14.0+cpu-cp313-cp313-manylinux_2_28_aarch64.whl": "092d5c12938850dfbd90a654b3c8dac34c33e300f88eb19ee6f4ef93992c6347",
    "torchvision-0.29.0+cpu-cp313-cp313-manylinux_2_28_aarch64.whl": "4d5138e00e117cfd5b7fe70af57d65d00abff2ce9f8581dc97805ecc62a510f5",
    "typing_extensions-4.16.0-py3-none-any.whl": "481caa481374e813c1b176ada14e97f1f67a4539ce9cfeb3f350d78d6370c2e8",
    "ultralytics-8.4.157-py3-none-any.whl": "66d70a59abbb9ec04592b3f8c22df731236e2b5da31b8df226b11d1b9f9d1eea",
    "ultralytics_platform-0.1.75-py3-none-any.whl": "0393ebafb715776f183e435f5704dabc511e05e85d2e2ad9f7b588e028285927",
    "ultralytics_thop-2.2.2-py3-none-any.whl": "10ef0f4ab45261516008660619fef114cddc2b7c8383d9d3bd76c1e551208ca3",
    "urllib3-2.8.0-py3-none-any.whl": "0cf3cae568d36aa9576b28dfb35f11328f1cb974ca7647d9475ebb86c75ac6e3"
}


class SetupError(RuntimeError):
    """Contains only installer-authored, safe diagnostic text."""


def log(message):
    print(f'[weapon] {message}', flush=True)


class Episode:
    """Count capture observation time, never network wait or inference backlog."""
    def __init__(self, hold=2.0, clear=1.0, max_gap=1.5, latched=False):
        self.hold, self.clear, self.max_gap = hold, clear, max_gap
        self.latched = latched
        self.started = self.absent = self.previous = None
        self.peak = 0.0
        self.samples = 0

    def unavailable(self):
        self.started = self.absent = self.previous = None
        self.peak = 0.0
        self.samples = 0
        # Preserve the incident latch through disconnection/restart.

    def observe(self, at, confidence):
        if self.previous is not None and (at <= self.previous or at - self.previous > self.max_gap):
            self.unavailable()
        self.previous = at
        if confidence is None:
            self.started, self.samples, self.peak = None, 0, 0.0
            if self.latched:
                if self.absent is None:
                    self.absent = at
                elif at - self.absent >= self.clear:
                    self.latched, self.absent = False, None
            return None
        self.absent = None
        if self.latched:
            return None
        if self.started is None:
            self.started = at
        self.samples += 1
        self.peak = max(self.peak, confidence)
        duration = at - self.started
        if duration >= self.hold and self.samples >= 3:
            self.latched = True
            return self.peak, duration
        return None


def event_payload(owner, confidence, duration, observed_at):
    return {
        'eventId': 'weapon_' + uuid.uuid4().hex,
        'userNo': owner,
        'occurredAt': dt.datetime.fromtimestamp(observed_at, dt.timezone.utc).isoformat(timespec='milliseconds'),
        'logType': 'SECURITY', 'subType': '흉기감지', 'severity': 'high',
        'val1': round(confidence, 4), 'val2': round(duration, 3),
        'description': f'칼(knife)이 {duration:.2f}초 연속 감지됨. 최고 감지 신뢰도 {confidence:.1%}.',
    }


class Outbox:
    """AI-owned text state only; never stores camera images or credentials."""
    def __init__(self, filename, binding):
        self.mutex = threading.Lock()
        self.db = sqlite3.connect(str(filename), check_same_thread=False)
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.execute('CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY CHECK(id=1), binding TEXT NOT NULL, active INTEGER NOT NULL)')
        self.db.execute('CREATE TABLE IF NOT EXISTS pending (id TEXT PRIMARY KEY, payload TEXT NOT NULL)')
        with self.db:
            self.db.execute('INSERT OR IGNORE INTO state VALUES(1, ?, 0)', (binding,))
        if self.db.execute('SELECT binding FROM state').fetchone()[0] != binding:
            self.db.close()
            raise ValueError('AI 상태의 소유자가 기존 CAMERA 연결과 다릅니다. 전송을 중단합니다.')

    def active(self):
        with self.mutex:
            return bool(self.db.execute('SELECT active FROM state').fetchone()[0])

    def record(self, payload):
        with self.mutex, self.db:
            if self.db.execute('SELECT COUNT(*) FROM pending').fetchone()[0] >= 5000:
                raise RuntimeError('미전송 기록 5000건 한도 도달. 네트워크/서버 확인 필요.')
            self.db.execute('INSERT INTO pending VALUES(?, ?)',
                            (payload['eventId'], json.dumps(payload, ensure_ascii=False, allow_nan=False)))
            self.db.execute('UPDATE state SET active=1 WHERE id=1')

    def clear(self):
        with self.mutex, self.db:
            self.db.execute('UPDATE state SET active=0 WHERE id=1')

    def first(self):
        with self.mutex:
            row = self.db.execute('SELECT payload FROM pending ORDER BY rowid LIMIT 1').fetchone()
            return json.loads(row[0]) if row else None

    def acknowledge(self, payload, status, body):
        if not (status == 200 and isinstance(body, dict) and body.get('success') is True
                and body.get('eventId') == payload['eventId']
                and type(body.get('userNo')) is int and body['userNo'] == payload['userNo']
                and type(body.get('logId')) is int and body['logId'] > 0):
            return False
        with self.mutex, self.db:
            self.db.execute('DELETE FROM pending WHERE id=?', (payload['eventId'],))
        return True

    def close(self):
        self.db.close()


@contextmanager
def exclusive_outbox(path, binding):
    """Linux flock prevents two services from recording the same episode."""
    if sys.platform != 'linux':
        raise RuntimeError('실제 저장 모드는 Raspberry Pi Linux에서 실행하세요.')
    import fcntl
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
    with os.fdopen(descriptor, 'a+b') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        box = Outbox(path, binding)
        try:
            yield box
        finally:
            box.close()


@dataclass(frozen=True)
class Identity:
    server: str
    token: str
    owner: int
    device: str

    @property
    def binding(self):
        return hashlib.sha256(f'{self.server}|{self.token}|{self.owner}|{self.device}'.encode()).hexdigest()

    def matches(self, body):
        return (isinstance(body, dict) and body.get('paired') is True and body.get('role') == 'CAMERA'
                and type(body.get('userNo')) is int and body['userNo'] == self.owner
                and body.get('deviceId') == self.device)

    @classmethod
    def read(cls, directory):
        credentials = json.loads((directory / 'credentials.json').read_text(encoding='utf-8'))
        owner = json.loads((directory / 'paired-owner.json').read_text(encoding='utf-8'))
        server, token = credentials.get('serverUrl', '').rstrip('/'), credentials.get('token', '')
        url = urlsplit(server)
        if (credentials.get('role') != 'CAMERA' or url.scheme != 'https' or not url.hostname
                or url.username or url.password or url.query or url.fragment or url.path
                or not isinstance(token, str) or len(token) < 32 or any(c.isspace() for c in token)):
            raise ValueError('기존 CAMERA 연결 정보를 확인하세요.')
        if (type(owner.get('userNo')) is not int or owner['userNo'] <= 0
                or not isinstance(owner.get('deviceId'), str) or not owner['deviceId']):
            raise ValueError('기존 CAMERA 연결 계정 정보가 필요합니다.')
        configured = os.environ.get('RENDER_SERVER_URL', server).rstrip('/')
        if configured != server:
            raise ValueError('CAMERA 저장 서버와 실행 환경의 서버가 다릅니다.')
        return cls(server, token, owner['userNo'], owner['deviceId'])


def http_json(session, identity, method, path, payload=None):
    with session.request(method, identity.server + path,
                         headers={'Authorization': 'Bearer ' + identity.token}, json=payload,
                         timeout=(5, 15), allow_redirects=False, stream=True) as response:
        data = bytearray()
        for chunk in response.iter_content(4096):
            data.extend(chunk)
            if len(data) > 65536:
                return response.status_code, None
        try:
            return response.status_code, json.loads(data)
        except (ValueError, UnicodeError):
            return response.status_code, None


def send_pending(box, identity, stop):
    import requests
    delay = 2
    with requests.Session() as session:
        while not stop.is_set():
            payload = box.first()
            if payload is None:
                stop.wait(1)
                continue
            try:
                status, body = http_json(session, identity, 'GET', '/api/devices/status')
                if status == 200 and not identity.matches(body):
                    log('CAMERA 연결 계정이 달라졌습니다. 저장된 기록을 보존하고 전송을 중단합니다.')
                    return
                if status == 200:
                    status, body = http_json(session, identity, 'POST', '/api/device/events', payload)
                    if box.acknowledge(payload, status, body):
                        log('흉기감지 기록 서버 저장 확인 (SECURITY / 흉기감지 / high).')
                        delay = 2
                        continue
                if status in (400, 401, 403, 404, 409, 410, 422):
                    log(f'HTTP {status}: 전송 중단, 미전송 기록 보존. CAMERA 이벤트 API/연결 확인 필요.')
                    return
                log(f'HTTP {status}: 기록 보존, {delay}초 후 동일 이벤트 재전송.')
            except requests.RequestException as error:
                # No exception text: URLs/proxy credentials might be embedded.
                log(f'통신 오류({type(error).__name__}): 기록 보존, {delay}초 후 재시도.')
            stop.wait(delay)
            delay = min(60, delay * 2)


def guarded_worker(target, arguments, stop, errors):
    """A stopped sender must fail the service, not silently disable recording."""
    try:
        target(*arguments)
        if not stop.is_set():
            raise RuntimeError('Background worker stopped unexpectedly')
    except Exception as error:
        errors.append(type(error).__name__)
        log(f'백그라운드 작업 중단({type(error).__name__}). 기록을 보존하고 서비스를 종료합니다.')
        stop.set()


class JpegParser:
    def __init__(self):
        self.buffer = bytearray()

    def feed(self, chunk):
        self.buffer.extend(chunk)
        images = []
        while self.buffer:
            start = self.buffer.find(b'\xff\xd8')
            if start < 0:
                self.buffer[:] = b'\xff' if self.buffer[-1] == 255 else b''
                break
            del self.buffer[:start]
            end = self.buffer.find(b'\xff\xd9', 2)
            newer = self.buffer.find(b'\xff\xd8', 2)
            if newer >= 0 and (end < 0 or newer < end):
                del self.buffer[:newer]
                continue
            if end < 0:
                if len(self.buffer) > MAX_JPEG:
                    self.buffer[:] = b'\xff' if self.buffer[-1] == 255 else b''
                break
            if 4 < end + 2 <= MAX_JPEG:
                images.append(bytes(self.buffer[:end + 2]))
            del self.buffer[:end + 2]
        return images


class CameraFeed:
    def __init__(self, url, stop):
        parsed = urlsplit(url)
        if (parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost', '::1')
                or parsed.username or parsed.password or parsed.query or parsed.fragment):
            raise ValueError('기존 Pi 로컬 카메라 HTTP 주소만 사용할 수 있습니다.')
        self.url, self.stop = url, stop
        self.lock = threading.Lock()
        self.latest = None
        self.sequence = self.generation = 0

    def get(self):
        with self.lock:
            return self.latest

    def run(self):
        import requests
        with requests.Session() as session:
            session.trust_env = False  # Never route the local camera through a proxy.
            while not self.stop.is_set():
                try:
                    with session.get(self.url, stream=True, timeout=(3, 3), allow_redirects=False) as response:
                        if response.status_code != 200 or 'multipart/' not in response.headers.get('Content-Type', ''):
                            raise ValueError('Camera MJPEG unavailable')
                        parser = JpegParser()
                        last_image = time.monotonic()
                        for chunk in response.iter_content(4096):
                            if self.stop.is_set():
                                return
                            images = parser.feed(chunk)
                            now = time.monotonic()
                            if images:
                                last_image = now
                                with self.lock:
                                    self.sequence += 1
                                    # Drain continuously; inference sees only the newest image.
                                    self.latest = (self.sequence, self.generation, now, time.time(), images[-1])
                            elif now - last_image > 3:
                                raise TimeoutError('No complete camera frame')
                except (requests.RequestException, ValueError, TimeoutError) as error:
                    log(f'카메라 대기({type(error).__name__}); 2초 후 다시 연결.')
                finally:
                    with self.lock:
                        self.latest = None
                        self.generation += 1
                self.stop.wait(2)


@contextmanager
def load_detector(args):
    # Keep library caches/settings in an AI-only temporary directory.
    sys.dont_write_bytecode = True
    os.environ.update(YOLO_AUTOINSTALL='false', YOLO_OFFLINE='true', OMP_NUM_THREADS='1',
                      OPENBLAS_NUM_THREADS='1', MKL_NUM_THREADS='1')
    if not args.model.is_file():
        raise ValueError('best.pt가 없습니다. AI 파일과 함께 복사하세요.')
    digest = hashlib.sha256(args.model.read_bytes()).hexdigest()
    if digest != MODEL_SHA256:
        raise ValueError('확인한 best.pt와 SHA256이 다릅니다. 모델 변경 여부를 먼저 확인하세요.')
    with tempfile.TemporaryDirectory(prefix='idla-weapon-') as config:
        os.environ['YOLO_CONFIG_DIR'] = config
        import cv2
        import numpy as np
        import torch
        from ultralytics import YOLO, settings
        settings.update({'sync': False})
        cv2.setNumThreads(1)
        torch.set_num_threads(1)
        torch.set_num_interop_threads(1)
        model = YOLO(str(args.model), task='detect')
        classes = [int(key) for key, value in model.names.items() if str(value).strip().lower() == 'knife']
        if len(classes) != 1:
            raise ValueError('모델에 knife 클래스가 정확히 하나 있어야 합니다.')

        def predict(frame):
            results = model.predict(frame, device='cpu', imgsz=args.imgsz, conf=args.confidence,
                                    classes=classes, verbose=False, save=False, show=False, max_det=20)
            confidence = results[0].boxes.conf
            return float(confidence.max().item()) if len(confidence) else None

        started = time.monotonic()
        predict(np.zeros((480, 640, 3), dtype=np.uint8))
        log(f'모델 준비 완료: knife, CPU 1스레드, 입력 {args.imgsz}, 첫 추론 {time.monotonic()-started:.2f}초.')
        if args.check_model:
            timings = []
            for _ in range(5):
                started = time.monotonic()
                predict(np.zeros((480, 640, 3), dtype=np.uint8))
                timings.append(time.monotonic() - started)
            log(f'준비 후 빈 영상 추론 5회 평균 {sum(timings)/len(timings):.3f}초, 최대 {max(timings):.3f}초 (현재 실행 장치).')
        yield predict, cv2, np


def monitor(args, detector, identity=None, box=None):
    predict, cv2, np = detector
    stop = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stop.set())
    feed = CameraFeed(args.stream, stop)
    errors = []
    reader = threading.Thread(target=guarded_worker, args=(feed.run, (), stop, errors),
                              daemon=True, name='weapon-camera-reader')
    workers = [reader]
    if box is not None:
        workers.append(threading.Thread(target=guarded_worker,
                                        args=(send_pending, (box, identity, stop), stop, errors),
                                        daemon=True, name='weapon-outbox'))
    gate = Episode(max_gap=args.max_gap, latched=box.active() if box else False)
    for worker in workers:
        worker.start()
    previous = None
    timings = deque(maxlen=30)
    report_at = time.monotonic() + 30
    log('감지 시작. 전송 모드.' if box else '감지 시작. 시험 모드: 서버/DB에 기록하지 않습니다.')
    try:
        while not stop.is_set():
            frame = feed.get()
            now = time.monotonic()
            if frame is None or now - frame[2] > args.max_age:
                gate.unavailable()
                stop.wait(0.05)
                continue
            sequence, generation, observed, wall_time, jpeg = frame
            if previous == (sequence, generation):
                stop.wait(0.02)
                continue
            if previous is not None and generation != previous[1]:
                gate.unavailable()
            previous = sequence, generation
            image = cv2.imdecode(np.frombuffer(jpeg, dtype=np.uint8), cv2.IMREAD_COLOR)
            if image is None:
                gate.unavailable()
                continue
            started = time.monotonic()
            confidence = predict(image)
            finished = time.monotonic()
            timings.append(finished - started)
            latest = feed.get()
            if (latest is None or latest[1] != generation or finished - observed > args.max_age):
                gate.unavailable()
            else:
                was_active = gate.latched
                event = gate.observe(observed, confidence)
                if event:
                    payload = event_payload(identity.owner if identity else None, *event, wall_time)
                    if box:
                        box.record(payload)
                    log(f'2초 연속 흉기감지: 신뢰도 {event[0]:.1%}, 관측 {event[1]:.2f}초. '
                        + ('전송 대기 저장 완료.' if box else '시험 감지 1건.'))
                elif was_active and not gate.latched and box:
                    box.clear()
            if finished >= report_at:
                log(f'최근 평균 추론 {sum(timings)/len(timings):.2f}초. '
                    f'관측 간격이 {args.max_gap:.1f}초를 넘으면 연속 감지를 초기화합니다.')
                report_at = finished + 30
            stop.wait(max(0, 1 / args.fps - (time.monotonic() - started)))
    finally:
        stop.set()
        for worker in workers:
            worker.join(timeout=25)
        if any(worker.is_alive() for worker in workers):
            # Do not close SQLite while a delayed network worker can still use it.
            log('작업 종료 시간 초과. 미전송 기록은 다음 실행에서 복구됩니다.')
            os._exit(1)
    if errors:
        raise RuntimeError('Background worker failed; pending events preserved')


def service_text(args):
    base = args.project / 'weapon_ai'
    for path in (args.project, base):
        if not re.fullmatch(r'/[A-Za-z0-9_./-]+', path.as_posix()) or '..' in path.parts:
            raise ValueError('서비스 설치 경로는 공백 없는 Linux 절대 경로여야 합니다.')
    if not re.fullmatch(r'[a-z_][a-z0-9_-]*', args.service_user):
        raise ValueError('Linux 서비스 사용자 이름을 확인하세요.')
    return f'''[Unit]
Description=I Don't Live Alone knife detection
After=network.target idla-camera.service
StartLimitIntervalSec=0

[Service]
Type=simple
User={args.service_user}
WorkingDirectory={args.project.as_posix()}
EnvironmentFile=/etc/idontlivealone/camera.env
Environment=PYTHONUNBUFFERED=1
Environment=PYTHONDONTWRITEBYTECODE=1
UMask=0077
StateDirectory=idla-weapon
StateDirectoryMode=0700
ExecStart={base.as_posix()}/.venv/bin/python -B {base.as_posix()}/weapon_detection.py --project {args.project.as_posix()} --model {base.as_posix()}/best.pt --outbox /var/lib/idla-weapon/events.sqlite3 --send-events
Nice=10
CPUQuota=100%
Restart=on-failure
RestartSec=10
TimeoutStopSec=35
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
'''


def verify_wheels(folder):
    if not WHEEL_HASHES:
        raise SetupError('설치 패키지 해시 목록이 아직 준비되지 않았습니다.')
    result = []
    for name, expected in sorted(WHEEL_HASHES.items()):
        if not re.fullmatch(r'[A-Za-z0-9_.+-]+\.whl', name):
            raise SetupError('설치 패키지 이름이 유효하지 않습니다.')
        path = folder / name
        if path.is_symlink() or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise SetupError(f'설치 패키지 누락/해시 불일치: {name}')
        result.append(path)
    return result


def runtime_ready(runtime):
    if not (runtime / 'pyvenv.cfg').is_file() or not (runtime / 'bin' / 'python').is_file():
        return False
    try:
        return subprocess.run([str(runtime / 'bin' / 'python'), '-m', 'pip', '--version'],
                              capture_output=True, timeout=30).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def install(args):
    """Offline, additive installation. Never edits existing camera/face files."""
    if sys.platform != 'linux' or os.geteuid() != 0:
        raise SetupError('설치는 Raspberry Pi에서 sudo로 실행하세요.')
    if platform.machine() != 'aarch64' or sys.version_info[:2] != (3, 13):
        raise SetupError('검증 대상은 ARM64 / Python 3.13입니다. 실제 Pi 환경을 먼저 확인하세요.')
    os.umask(0o022)  # New AI runtime must be readable by the service user.
    unit = service_text(args)
    project = args.project
    if not project.is_dir() or project.resolve() != project:
        raise SetupError('기존 카메라 작업 경로가 없거나 심볼릭 링크입니다.')
    def command(*parts, timeout=300):
        result = subprocess.run([str(p) for p in parts], capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise SetupError(f'설치 단계 실패: {Path(str(parts[0])).name}. 기존 카메라 서비스는 변경하지 않았습니다.')
        return result.stdout.strip()
    camera_user = command('systemctl', 'show', 'idla-camera.service', '-p', 'User', '--value')
    camera_project = command('systemctl', 'show', 'idla-camera.service', '-p', 'WorkingDirectory', '--value')
    if camera_user != args.service_user or camera_project != str(project):
        raise SetupError('기존 카메라 서비스의 계정/작업 경로가 예상과 다릅니다.')
    if not (project / 'fin_camera.py').is_file() or not Path('/etc/idontlivealone/camera.env').is_file():
        raise SetupError('기존 카메라 파일 또는 서비스 환경 설정이 없습니다.')
    if not args.model.is_file() or hashlib.sha256(args.model.read_bytes()).hexdigest() != MODEL_SHA256:
        raise SetupError('설치할 best.pt가 확인한 모델과 다릅니다.')
    wheels = verify_wheels(args.wheels)
    if shutil.disk_usage(project).free < 1_500_000_000:
        raise SetupError('AI 환경 설치에는 Pi에 최소 1.5GB의 빈 공간이 필요합니다.')
    target = project / 'weapon_ai'
    unit_path = Path('/etc/systemd/system/idla-weapon.service')
    if target.is_symlink() or unit_path.is_symlink():
        raise SetupError('AI 설치 대상이 심볼릭 링크입니다.')
    if unit_path.exists() and unit_path.read_text(encoding='utf-8') != unit:
        raise SetupError('다른 idla-weapon.service가 이미 있습니다. 덮어쓰지 않았습니다.')
    source = Path(__file__).resolve().read_bytes()
    marker = hashlib.sha256(source).hexdigest() + ':' + MODEL_SHA256
    if target.exists():
        ready = target / '.installation-source'
        if not ready.is_file() or ready.read_text(encoding='ascii') != marker:
            raise SetupError('기존 weapon_ai 폴더가 다른 설치에 속합니다. 덮어쓰지 않았습니다.')
    else:
        target.mkdir(mode=0o755)
        (target / '.installation-source').write_text(marker, encoding='ascii')
    for name, content in [('weapon_detection.py', source), ('best.pt', args.model.read_bytes())]:
        destination = target / name
        if destination.is_symlink():
            raise SetupError('AI 설치 파일이 심볼릭 링크입니다.')
        if destination.exists():
            if destination.read_bytes() != content:
                raise SetupError('기존 AI 파일이 다릅니다. 덮어쓰지 않았습니다.')
        else:
            with destination.open('xb') as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            destination.chmod(0o644)
    runtime = target / '.venv'
    if runtime.is_symlink():
        raise SetupError('AI Python 환경이 심볼릭 링크입니다.')
    # A failed first installation may leave only part of the venv behind.
    # The matching installation marker above confines this retry to our own AI environment.
    if not runtime_ready(runtime):
        command(sys.executable, '-m', 'venv', runtime)
    if not runtime_ready(runtime):
        raise SetupError('AI 전용 Python 환경 생성이 완료되지 않았습니다.')
    python = runtime / 'bin' / 'python'
    log(f'AI 전용 환경에 검증한 패키지 {len(wheels)}개 설치 중. 기존 ai_env는 유지합니다.')
    command(python, '-m', 'pip', '--isolated', 'install', '--no-index', '--no-deps',
            '--no-cache-dir', '--disable-pip-version-check', *wheels, timeout=900)
    command(python, '-m', 'pip', '--isolated', 'check')
    result = command('runuser', '-u', args.service_user, '--', python, '-B',
                     target / 'weapon_detection.py', '--check-model', '--model', target / 'best.pt')
    if 'MODEL CHECK OK' not in result:
        raise SetupError('Pi에서 실제 모델 로드/추론 확인이 실패했습니다. 서비스는 등록하지 않았습니다.')
    for line in result.splitlines():
        if line.startswith('[weapon]'):
            log(line.removeprefix('[weapon] '))
    preview = target / 'idla-weapon.service'
    if preview.exists() and preview.read_text(encoding='utf-8') != unit:
        raise SetupError('기존 AI 서비스 검증 파일과 내용이 다릅니다.')
    if not preview.exists():
        preview.write_text(unit, encoding='utf-8')
    command('systemd-analyze', 'verify', preview)
    if not unit_path.exists():
        with unit_path.open('x', encoding='utf-8') as stream:
            stream.write(unit)
            stream.flush()
            os.fsync(stream.fileno())
        unit_path.chmod(0o644)
    command('systemctl', 'daemon-reload')
    command('systemctl', 'enable', 'idla-weapon.service')
    if not args.no_start:
        command('systemctl', 'start', 'idla-weapon.service')
        command('systemctl', 'is-active', '--quiet', 'idla-weapon.service')
    log('AI INSTALL OK. 자동 실행 등록 완료. 실제 영상 감지와 DB 기록 확인은 별도입니다.')
    return 0


def armed_sd_cmdline(original):
    """Pure preparation helper; this function never reads or arms a real SD."""
    text = original.decode('utf-8')
    if '\x00' in text or len(text.strip().splitlines()) != 1:
        raise ValueError('Expected one original kernel command line')
    prefixes = ('systemd.run=', 'systemd.run_success_action=',
                'systemd.run_failure_action=', 'systemd.unit=')
    if any(word.startswith(prefixes) for word in text.split()):
        raise ValueError('Existing boot override requires review')
    return (text.rstrip('\r\n') + ' ' + ' '.join(SD_BOOT_OPTIONS) + '\n').encode('utf-8')


def sd_wrapper_text():
    return ('#!/bin/sh\nset -eu\n'
            'exec /usr/bin/python3 -B /boot/firmware/weapon-ai/weapon_detection.py --sd-bootstrap\n')


def sd_read_regular(path, limit=None):
    if path.is_symlink() or not path.is_file():
        raise ValueError('Missing or linked bootstrap file')
    if limit is not None and path.stat().st_size > limit:
        raise ValueError('Oversized bootstrap metadata')
    return path.read_bytes()


def sd_replace_synced(path, content):
    """Atomically replace only our result or the explicitly reviewed cmdline."""
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise ValueError('Unsafe bootstrap destination')
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.installing')
    try:
        with temporary.open('xb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        if hasattr(os, 'sync'):
            os.sync()
        if sd_read_regular(path) != content:
            raise OSError('Bootstrap write verification failed')
    finally:
        if temporary.exists():
            temporary.unlink()


def restore_sd_cmdline(boot, bundle, manifest):
    original = sd_read_regular(bundle / 'original-cmdline.txt', 16384)
    if hashlib.sha256(original).hexdigest() != manifest.get('originalCmdlineSha256'):
        raise ValueError('Original command line checksum differs')
    partuuid = manifest.get('rootPartuuid')
    if not isinstance(partuuid, str) or not re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{2}', partuuid):
        raise ValueError('Expected reviewed MBR root partition identity')
    roots = [word for word in original.decode('utf-8').split() if word.startswith('root=')]
    if roots != ['root=PARTUUID=' + partuuid]:
        raise ValueError('Original command line root partition differs')
    armed = armed_sd_cmdline(original)
    if hashlib.sha256(armed).hexdigest() != manifest.get('armedCmdlineSha256'):
        raise ValueError('Armed command line checksum differs')
    current = sd_read_regular(boot / 'cmdline.txt', 16384)
    if current not in (original, armed):
        raise ValueError('Command line changed after preparation')
    if current != original:
        sd_replace_synced(boot / 'cmdline.txt', original)
    # Exact bytes, including the original line ending, must survive restoration.
    if sd_read_regular(boot / 'cmdline.txt') != original:
        raise OSError('Original command line restoration failed')


def verify_sd_payload(bundle, manifest):
    if manifest.get('user') != 'yeonh0115' or manifest.get('projectDir') != PROJECT.as_posix():
        raise ValueError('Bootstrap target differs from reviewed camera project')
    expected = {
        'weapon_detection.py': hashlib.sha256(Path(__file__).resolve().read_bytes()).hexdigest(),
        'best.pt': MODEL_SHA256,
        'weapon-bootstrap.sh': hashlib.sha256(sd_wrapper_text().encode('utf-8')).hexdigest(),
        **{'wheels/' + name: digest for name, digest in WHEEL_HASHES.items()},
    }
    if manifest.get('payloadSha256') != expected:
        raise ValueError('Bootstrap payload manifest differs from reviewed files')
    for name in ('weapon_detection.py', 'best.pt', 'weapon-bootstrap.sh'):
        if hashlib.sha256(sd_read_regular(bundle / name)).hexdigest() != expected[name]:
            raise ValueError('Bootstrap payload checksum differs')
    wheels = bundle / 'wheels'
    if wheels.is_symlink() or not wheels.is_dir():
        raise ValueError('Bootstrap wheels directory missing or linked')
    verify_wheels(wheels)


def sd_bootstrap(boot, installer=None):
    """One boot only: restore normal boot before touching the AI installation.

    The CLI is Linux-root only. Explicit boot and installer arguments let tests
    exercise this flow with temporary files and no operating-system changes.
    """
    boot = Path(boot)
    bundle = boot / 'weapon-ai'
    result = {'status': 'failed', 'cmdlineRestored': False, 'stage': 'manifest'}
    exit_code = 1
    try:
        if boot.is_symlink() or not boot.is_dir() or bundle.is_symlink() or not bundle.is_dir():
            raise ValueError('Bootstrap directory missing or linked')
        manifest = json.loads(sd_read_regular(bundle / 'weapon-bootstrap.json', 65536))
        if not isinstance(manifest, dict) or type(manifest.get('version')) is not int or manifest['version'] != 1:
            raise ValueError('Unknown bootstrap manifest version')
        result['stage'] = 'restore-cmdline'
        restore_sd_cmdline(boot, bundle, manifest)
        result['cmdlineRestored'] = True
        # A later model/package/install error cannot leave the special boot armed.
        result['stage'] = 'payload'
        verify_sd_payload(bundle, manifest)
        result['stage'] = 'install'
        args = argparse.Namespace(project=PROJECT, service_user='yeonh0115',
                                  model=bundle / 'best.pt', wheels=bundle / 'wheels', no_start=True)
        if (install if installer is None else installer)(args) != 0:
            raise RuntimeError('AI installer did not confirm success')
        result.update(status='success', stage='complete', services=['idla-weapon.service'])
        exit_code = 0
    except Exception as error:
        # Do not serialize exception text, commands, environment, or credentials.
        result['errorType'] = type(error).__name__
        log(f'SD 설치 중단({type(error).__name__}); 결과 파일의 단계만 확인하세요.')
    result['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
    try:
        # The result is an AI-owned new file, never a camera/face setting.
        if bundle.is_symlink() or not bundle.is_dir():
            raise ValueError('Result directory missing or linked')
        sd_replace_synced(bundle / 'weapon-install-result.json',
                          (json.dumps(result, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    except Exception as error:
        log(f'SD 설치 결과 기록 실패({type(error).__name__}).')
        return 1
    return exit_code


def self_test():
    import unittest

    @contextmanager
    def sd_fixture():
        from unittest.mock import patch
        sample_model, sample_wheel = b'test-only-model', b'test-only-wheel'
        wheel_name = 'sample-1-py3-none-any.whl'
        pins = {wheel_name: hashlib.sha256(sample_wheel).hexdigest()}
        model_hash = hashlib.sha256(sample_model).hexdigest()
        with tempfile.TemporaryDirectory(prefix='idla-weapon-sd-test-') as directory, \
                patch.dict(globals(), {'WHEEL_HASHES': pins, 'MODEL_SHA256': model_hash}):
            boot = Path(directory)
            bundle = boot / 'weapon-ai'
            (bundle / 'wheels').mkdir(parents=True)
            original = b'console=serial0,115200 root=PARTUUID=ad3978a6-02 rootwait\r\n'
            files = {'weapon_detection.py': Path(__file__).resolve().read_bytes(),
                     'best.pt': sample_model, 'weapon-bootstrap.sh': sd_wrapper_text().encode('utf-8'),
                     'wheels/' + wheel_name: sample_wheel}
            for name, content in files.items():
                (bundle / name).write_bytes(content)
            manifest = dict(version=1, rootPartuuid='ad3978a6-02', user='yeonh0115',
                            projectDir=PROJECT.as_posix(),
                            originalCmdlineSha256=hashlib.sha256(original).hexdigest(),
                            armedCmdlineSha256=hashlib.sha256(armed_sd_cmdline(original)).hexdigest(),
                            payloadSha256={name: hashlib.sha256(content).hexdigest() for name, content in files.items()})
            (bundle / 'weapon-bootstrap.json').write_text(json.dumps(manifest), encoding='utf-8')
            (bundle / 'original-cmdline.txt').write_bytes(original)
            (boot / 'cmdline.txt').write_bytes(armed_sd_cmdline(original))
            yield boot, bundle, original, manifest

    class Tests(unittest.TestCase):
        def test_two_seconds_and_one_event(self):
            gate = Episode()
            self.assertIsNone(gate.observe(0, .6))
            self.assertIsNone(gate.observe(1, .8))
            self.assertEqual(gate.observe(2, .7), (.8, 2))
            self.assertIsNone(gate.observe(3, .9))

        def test_negative_breaks_continuity(self):
            gate = Episode()
            for at, confidence in [(0, .9), (1, .9), (1.5, None), (2, .9), (3, .9)]:
                self.assertIsNone(gate.observe(at, confidence))
            self.assertIsNotNone(gate.observe(4, .9))

        def test_slow_or_repeated_frames_do_not_confirm(self):
            gate = Episode()
            for at in (0, 3, 6, 6, 6.5, 7):
                self.assertIsNone(gate.observe(at, .9))

        def test_clear_then_new_episode(self):
            gate = Episode(latched=True)
            gate.observe(0, None)
            gate.observe(.5, .9)  # one missed inference must not create another incident
            self.assertTrue(gate.latched)
            gate.observe(1, None)
            gate.observe(2, None)
            self.assertFalse(gate.latched)
            gate.observe(3, .9)
            gate.observe(4, .9)
            self.assertIsNotNone(gate.observe(5, .9))

        def test_disconnect_preserves_active(self):
            gate = Episode(latched=True)
            gate.unavailable()
            self.assertIsNone(gate.observe(100, .9))
            self.assertTrue(gate.latched)
            gate.observe(101, None)
            gate.unavailable()
            gate.observe(200, None)
            self.assertTrue(gate.latched)

        def test_disconnect_resets_unconfirmed(self):
            gate = Episode()
            gate.observe(0, .9)
            gate.observe(1, .9)
            gate.unavailable()
            self.assertIsNone(gate.observe(2, .9))

        def test_jpeg_split_and_incomplete(self):
            parser = JpegParser()
            self.assertEqual(parser.feed(b'header\xff'), [])
            self.assertEqual(parser.feed(b'\xd8broken\xff\xd8good\xff'), [])
            self.assertEqual(parser.feed(b'\xd9tail'), [b'\xff\xd8good\xff\xd9'])

        def test_jpeg_memory_bound(self):
            parser = JpegParser()
            self.assertEqual(parser.feed(b'\xff\xd8' + b'x' * MAX_JPEG), [])
            self.assertLessEqual(len(parser.buffer), 1)

        def test_outbox_retry_exact_payload_and_ack(self):
            box = Outbox(':memory:', 'test-owner')
            self.addCleanup(box.close)
            event = event_payload(7, .9, 2.1, 1_800_000_000)
            box.record(event)
            self.assertTrue(box.active())
            self.assertEqual(box.first(), event)
            self.assertFalse(box.acknowledge(event, 500, {}))
            self.assertEqual(box.first(), event)
            ack = dict(success=True, eventId=event['eventId'], userNo=8, logId=1)
            self.assertFalse(box.acknowledge(event, 200, ack))
            ack['userNo'] = 7
            self.assertTrue(box.acknowledge(event, 200, ack))
            self.assertIsNone(box.first())
            self.assertTrue(box.active())  # ACK does not re-arm detection
            box.clear()
            self.assertFalse(box.active())

        def test_outbox_survives_restart_without_rearming(self):
            with tempfile.TemporaryDirectory(prefix='idla-weapon-test-') as directory:
                path = Path(directory) / 'events.sqlite3'
                event = event_payload(7, .9, 2, 1_800_000_000)
                box = Outbox(path, 'owner-one')
                box.record(event)
                box.close()
                restored = Outbox(path, 'owner-one')
                try:
                    self.assertEqual(restored.first(), event)
                    self.assertTrue(restored.active())
                    gate = Episode(latched=restored.active())
                    self.assertIsNone(gate.observe(0, .9))
                finally:
                    restored.close()
                with self.assertRaises(ValueError):
                    Outbox(path, 'owner-two')

        def test_service_is_preview_only_and_uses_new_paths(self):
            args = argparse.Namespace(project=PROJECT, service_user='yeonh0115')
            text = service_text(args)
            self.assertIn('After=network.target idla-camera.service', text)
            self.assertIn('weapon_ai/.venv/bin/python -B', text)
            self.assertIn('--send-events', text)
            self.assertNotIn('ExecStart=/home/yeonh0115/cctvStreaming/fin_camera.py', text)

        def test_event_contract(self):
            event = event_payload(7, .91234, 2.12345, 1_800_000_000)
            self.assertEqual((event['logType'], event['subType'], event['severity']), ('SECURITY', '흉기감지', 'high'))
            self.assertEqual(event['val1'], .9123)
            self.assertEqual(event['val2'], 2.123)
            self.assertIsNotNone(dt.datetime.fromisoformat(event['occurredAt']).utcoffset())
            self.assertRegex(event['eventId'], r'^weapon_[0-9a-f]{32}$')
            self.assertNotEqual(event_payload(7, .9, 2, 0)['eventId'], event['eventId'])

        def test_status_owner_and_role(self):
            identity = Identity('https://example.invalid', 'x'*48, 7, 'camera-a')
            body = dict(paired=True, role='CAMERA', userNo=7, deviceId='camera-a')
            self.assertTrue(identity.matches(body))
            for key, value in [('paired', False), ('role', 'SENSOR'), ('userNo', 8), ('deviceId', 'other')]:
                self.assertFalse(identity.matches({**body, key: value}))

        def test_local_camera_only(self):
            for url in ['https://example.com/video_feed', 'http://user:pass@localhost/video_feed', 'http://192.168.1.1/video_feed']:
                with self.assertRaises(ValueError):
                    CameraFeed(url, threading.Event())

        def test_worker_early_return_fails_service(self):
            stop, errors = threading.Event(), []
            guarded_worker(lambda: None, (), stop, errors)
            self.assertTrue(stop.is_set())
            self.assertEqual(errors, ['RuntimeError'])

        def test_worker_exception_fails_service(self):
            stop, errors = threading.Event(), []
            def broken():
                raise ValueError('private detail must not be printed')
            guarded_worker(broken, (), stop, errors)
            self.assertTrue(stop.is_set())
            self.assertEqual(errors, ['ValueError'])

        def test_worker_requested_shutdown_succeeds(self):
            stop, errors = threading.Event(), []
            stop.set()
            guarded_worker(lambda: None, (), stop, errors)
            self.assertEqual(errors, [])

        def test_installer_rejects_non_linux_without_writing(self):
            if sys.platform != 'linux':
                with self.assertRaises(SetupError):
                    install(argparse.Namespace())

        def test_installer_rejects_changed_wheel(self):
            from unittest.mock import patch
            with tempfile.TemporaryDirectory(prefix='idla-weapon-test-') as directory:
                path = Path(directory) / 'sample-1-py3-none-any.whl'
                path.write_bytes(b'original')
                pins = {path.name: hashlib.sha256(b'original').hexdigest()}
                with patch.dict(globals(), {'WHEEL_HASHES': pins}):
                    self.assertEqual(verify_wheels(Path(directory)), [path])
                    path.write_bytes(b'changed')
                    with self.assertRaises(SetupError):
                        verify_wheels(Path(directory))

        def test_partial_runtime_is_not_accepted_as_installed(self):
            from unittest.mock import patch
            with tempfile.TemporaryDirectory(prefix='idla-weapon-test-') as directory:
                runtime = Path(directory)
                (runtime / 'pyvenv.cfg').write_text('partial', encoding='utf-8')
                self.assertFalse(runtime_ready(runtime))
                (runtime / 'bin').mkdir()
                (runtime / 'bin' / 'python').write_bytes(b'placeholder')
                with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 1)):
                    self.assertFalse(runtime_ready(runtime))
                with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)):
                    self.assertTrue(runtime_ready(runtime))

        def test_sd_restores_exact_original_before_install_without_starting(self):
            with sd_fixture() as (boot, bundle, original, _):
                calls = []
                def fake_install(args):
                    self.assertEqual((boot / 'cmdline.txt').read_bytes(), original)
                    self.assertTrue(args.no_start)
                    self.assertEqual(args.project, PROJECT)
                    self.assertEqual(args.model, bundle / 'best.pt')
                    calls.append(args)
                    return 0
                self.assertEqual(sd_bootstrap(boot, fake_install), 0)
                self.assertEqual(len(calls), 1)
                result = json.loads((bundle / 'weapon-install-result.json').read_text(encoding='utf-8'))
                self.assertEqual(result['status'], 'success')
                self.assertTrue(result['cmdlineRestored'])

        def test_sd_payload_failure_still_restores_normal_boot(self):
            from unittest.mock import Mock
            with sd_fixture() as (boot, bundle, original, _):
                (bundle / 'best.pt').write_bytes(b'changed')
                installer = Mock(return_value=0)
                self.assertEqual(sd_bootstrap(boot, installer), 1)
                installer.assert_not_called()
                self.assertEqual((boot / 'cmdline.txt').read_bytes(), original)
                result = json.loads((bundle / 'weapon-install-result.json').read_text(encoding='utf-8'))
                self.assertEqual(result['stage'], 'payload')
                self.assertTrue(result['cmdlineRestored'])

        def test_sd_refuses_changed_original_or_unrelated_current_cmdline(self):
            from unittest.mock import Mock
            for change_original in (True, False):
                with self.subTest(change_original=change_original), sd_fixture() as (boot, bundle, _, _):
                    target = bundle / 'original-cmdline.txt' if change_original else boot / 'cmdline.txt'
                    target.write_bytes(target.read_bytes() + b'changed')
                    current = (boot / 'cmdline.txt').read_bytes()
                    installer = Mock(return_value=0)
                    self.assertEqual(sd_bootstrap(boot, installer), 1)
                    installer.assert_not_called()
                    self.assertEqual((boot / 'cmdline.txt').read_bytes(), current)

        def test_sd_installer_failure_is_secret_free_and_boot_is_restored(self):
            from unittest.mock import Mock
            with sd_fixture() as (boot, bundle, original, _):
                installer = Mock(side_effect=RuntimeError('secret-test-value-do-not-print'))
                self.assertEqual(sd_bootstrap(boot, installer), 1)
                self.assertEqual((boot / 'cmdline.txt').read_bytes(), original)
                text = (bundle / 'weapon-install-result.json').read_text(encoding='utf-8')
                self.assertNotIn('secret-test-value', text)
                self.assertEqual(json.loads(text)['errorType'], 'RuntimeError')

        def test_sd_checks_armed_hash_and_exact_payload_manifest(self):
            from unittest.mock import Mock
            for stage in ('restore-cmdline', 'payload'):
                with self.subTest(stage=stage), sd_fixture() as (boot, bundle, original, manifest):
                    if stage == 'restore-cmdline':
                        manifest['armedCmdlineSha256'] = '0' * 64
                    else:
                        manifest['payloadSha256']['../unrelated'] = '0' * 64
                    (bundle / 'weapon-bootstrap.json').write_text(json.dumps(manifest), encoding='utf-8')
                    installer = Mock(return_value=0)
                    self.assertEqual(sd_bootstrap(boot, installer), 1)
                    installer.assert_not_called()
                    result = json.loads((bundle / 'weapon-install-result.json').read_text(encoding='utf-8'))
                    self.assertEqual(result['stage'], stage)
                    self.assertEqual((boot / 'cmdline.txt').read_bytes() == original, stage == 'payload')

        def test_sd_retry_accepts_already_restored_original(self):
            from unittest.mock import Mock
            with sd_fixture() as (boot, _, original, _):
                (boot / 'cmdline.txt').write_bytes(original)
                self.assertEqual(sd_bootstrap(boot, Mock(return_value=0)), 0)
                self.assertEqual((boot / 'cmdline.txt').read_bytes(), original)

        def test_sd_rejects_existing_boot_override_and_multiline(self):
            for text in (b'root=x systemd.run=/existing\n', b'root=x\nconsole=y\n', b'root=x\x00'):
                with self.subTest(text=text), self.assertRaises(ValueError):
                    armed_sd_cmdline(text)

    return 0 if unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Tests)).wasSuccessful() else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--model', type=Path, default=Path(__file__).resolve().with_name('best.pt'))
    parser.add_argument('--project', type=Path, default=PROJECT)
    parser.add_argument('--stream', default='http://127.0.0.1:5002/video_feed')
    parser.add_argument('--confidence', type=float, default=.5)
    parser.add_argument('--imgsz', type=int, default=320)
    parser.add_argument('--fps', type=float, default=2.0)
    parser.add_argument('--max-gap', type=float, default=1.5)
    parser.add_argument('--max-age', type=float, default=2.0)
    parser.add_argument('--outbox', type=Path, default=Path('/var/lib/idla-weapon/events.sqlite3'))
    parser.add_argument('--service-user', default='yeonh0115')
    parser.add_argument('--wheels', type=Path, default=Path(__file__).resolve().parent / 'wheels')
    parser.add_argument('--no-start', action='store_true', help='설치 시 부팅 등록만 하고 즉시 시작하지 않음')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--self-test', action='store_true')
    mode.add_argument('--check-model', action='store_true')
    mode.add_argument('--print-service', action='store_true')
    mode.add_argument('--send-events', action='store_true')
    mode.add_argument('--install', action='store_true')
    mode.add_argument('--sd-bootstrap', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.print_service:
        print(service_text(args))
        return 0
    if args.install:
        return install(args)
    if args.sd_bootstrap:
        if sys.platform != 'linux' or os.geteuid() != 0:
            raise SetupError('SD 부팅 설치는 Raspberry Pi의 승인된 1회 부팅에서만 실행하세요.')
        return sd_bootstrap(Path('/boot/firmware'))
    if args.no_start:
        parser.error('--no-start는 --install과 함께 사용하세요.')
    if (not math.isfinite(args.confidence) or not 0 < args.confidence < 1
            or args.imgsz not in (320, 416, 512, 640)
            or not math.isfinite(args.fps) or not 1 <= args.fps <= 5
            or not math.isfinite(args.max_gap) or not .5 <= args.max_gap <= 1.5
            or not math.isfinite(args.max_age) or not .25 <= args.max_age <= 2):
        parser.error('confidence: 0~1, imgsz: 320/416/512/640, fps: 1~5, max-gap: 0.5~1.5, max-age: 0.25~2')
    with load_detector(args) as detector:
        if args.check_model:
            log('MODEL CHECK OK. 빈 영상 추론만 확인했으며 실제 칼 인식률/Pi 속도 검증은 별도입니다.')
            return 0
        if args.send_events:
            state = Path(os.environ.get('DEVICE_STATE_DIR', str(args.project / 'device_state')))
            if not state.is_absolute():
                state = args.project / state
            identity = Identity.read(state)
            with exclusive_outbox(args.outbox, identity.binding) as box:
                monitor(args, detector, identity, box)
        else:
            monitor(args, detector)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(0)
    except Exception as error:
        # Deliberately omit exception text and tracebacks: keep credentials private.
        log(str(error) if isinstance(error, SetupError) else
            f'실행 중단({type(error).__name__}). 모델/설치 패키지/장치 연결/AI 상태 경로를 확인하세요.')
        raise SystemExit(1)
