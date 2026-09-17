"""Headless device enrollment. Credentials stay local; only the pairing code is shown."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import ssl
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import uuid

DEFAULT_SERVER = "https://idontlivealone.onrender.com"


def private_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
        if hasattr(os, "O_DIRECTORY"):
            descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
    finally:
        temporary.unlink(missing_ok=True)


@contextmanager
def credentials_lock(directory):
    """Camera and recognition services must never enroll with different tokens."""
    path = Path(directory)
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(path / ".credentials.lock", os.O_RDWR | os.O_CREAT, 0o600)
    handle = os.fdopen(descriptor, "r+b")
    try:
        if os.name == "nt":
            import msvcrt
            if handle.read(1) == b"":
                handle.write(b"0")
                handle.flush()
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX)
        yield
    finally:
        handle.close()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class JsonTransport:
    def __init__(self):
        ca_file = os.environ.get("REQUESTS_CA_BUNDLE") or os.environ.get("CURL_CA_BUNDLE")
        context = ssl.create_default_context(cafile=ca_file or None)
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=context))

    def __call__(self, method, url, token, payload=None):
        headers = {"Authorization": "Bearer " + token}
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=15) as response:
                data = response.read(65537)
                if len(data) > 65536:
                    return response.status, None
                try:
                    return response.status, json.loads(data)
                except (ValueError, UnicodeError):
                    return response.status, None
        except urllib.error.HTTPError as error:
            return error.code, None


class DeviceClient:
    def __init__(self, state_dir, role, name=None, server_url=DEFAULT_SERVER, transport=None, logger=print):
        if role not in ("CAMERA", "SENSOR", "REPORT"):
            raise ValueError("Device role must be CAMERA, SENSOR or REPORT")
        server_url = server_url.rstrip("/")
        parsed = urlsplit(server_url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("Enrollment requires an HTTPS server URL without credentials or query parameters")
        self.root = Path(state_dir)
        self.role, self.name, self.server_url = role, name or role.title(), server_url
        self.transport = transport or JsonTransport()
        self.log = logger
        self.identity = None
        with credentials_lock(self.root):
            path = self.root / "credentials.json"
            if not path.exists():
                private_json(path, {"version": 1, "serverUrl": server_url, "role": role, "token": secrets.token_urlsafe(48)})
            state = json.loads(path.read_text(encoding="utf-8"))
            if state.get("role") != role or state.get("serverUrl") != server_url:
                raise ValueError("Saved device role/server differs; preserve credentials and check configuration")
            token = state.get("token")
            if not isinstance(token, str) or len(token) < 32 or any(c.isspace() for c in token):
                raise ValueError("Saved device credential is invalid; restore the private backup")
            os.chmod(path, 0o600)
            self.token = token

    @property
    def user_no(self):
        return self.identity.get("userNo") if self.identity and self.identity.get("paired") else None

    @property
    def device_id(self):
        return self.identity.get("deviceId") if self.identity else None

    def headers(self):
        return {"Authorization": "Bearer " + self.token}

    def request(self, method, path, payload=None):
        return self.transport(method, self.server_url + path, self.token, payload)

    def accept_status(self, body):
        if not isinstance(body, dict) or body.get("role") != self.role or not body.get("deviceId") or not isinstance(body.get("paired"), bool):
            raise ValueError("Device status response is invalid")
        if body["paired"]:
            value = body.get("userNo")
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                raise ValueError("Paired device status must include a positive user number")
            # Model labels / pending events must not silently move to another
            # account after an unlink, re-pair or process restart.
            with credentials_lock(self.root):
                owner_path = self.root / "paired-owner.json"
                owner = {"userNo": value, "deviceId": body["deviceId"]}
                if owner_path.exists():
                    if json.loads(owner_path.read_text(encoding="utf-8")) != owner:
                        self.identity = None
                        raise ValueError("Device owner changed; preserve local data and review account migration")
                else:
                    private_json(owner_path, owner)
        previous = self.identity
        if previous and previous.get("paired") and body.get("paired") and previous["userNo"] != body["userNo"]:
            self.identity = None
            raise ValueError("Device owner changed; restart after reviewing existing local data")
        self.identity = body
        private_json(self.root / "status.json", body)
        if body.get("pairingCode"):
            private_json(self.root / "pairing-code.json", {
                key: body.get(key) for key in ("deviceId", "role", "name", "pairingCode", "expiresAt")
            })
        elif body["paired"]:
            (self.root / "pairing-code.json").unlink(missing_ok=True)
        return body

    def enroll(self):
        status, body = self.request("POST", "/api/devices/enroll", {"role": self.role, "name": self.name})
        if status != 200:
            raise RuntimeError(f"Device enrollment HTTP {status}")
        return self.accept_status(body)

    def refresh(self):
        status, body = self.request("GET", "/api/devices/status")
        if status == 200:
            return self.accept_status(body)
        if status in (401, 403, 404):
            self.identity = None
        raise RuntimeError(f"Device status HTTP {status}")

    def wait_paired(self, stop=None, interval=5):
        """Startup polling does not consume sensor bytes or start door GPIO work."""
        last_code = None
        while stop is None or not stop.is_set():
            try:
                body = self.enroll() if self.identity is None else self.refresh()
                if body["paired"]:
                    return body
                # Refresh the same token's expired code; retries never create a new identity.
                body = self.enroll()
                code = body.get("pairingCode")
                if code and code != last_code:
                    self.log(f"[pairing] {self.role} connection code: {code}; enter it in the app", flush=True)
                    last_code = code
            except Exception as error:
                self.log(f"[pairing] {type(error).__name__}; waiting for HTTPS server/app registration", flush=True)
            if stop is None:
                time.sleep(interval)
            elif stop.wait(interval):
                return None
        return None


def device_from_environment(role, source_dir, env=None):
    env = os.environ if env is None else env
    source = Path(source_dir)
    state = Path(env.get("DEVICE_STATE_DIR", str(source / "device_state")))
    if not state.is_absolute():
        state = source / state
    return DeviceClient(state, role, env.get("DEVICE_NAME"), env.get("RENDER_SERVER_URL", DEFAULT_SERVER))


def main():
    parser = argparse.ArgumentParser(description="Prepare an existing device's private credentials and show its app connection code")
    parser.add_argument("--state-dir", required=True)
    parser.add_argument("--role", choices=("CAMERA", "SENSOR", "REPORT"), required=True)
    parser.add_argument("--name")
    parser.add_argument("--server", default=DEFAULT_SERVER)
    parser.add_argument("--enroll", action="store_true", help="Enroll or refresh the code with the same saved token (the default action)")
    args = parser.parse_args()
    try:
        client = DeviceClient(args.state_dir, args.role, args.name, args.server)
        body = client.enroll()
        if body["paired"]:
            print(f"{args.role}: already paired; credentials preserved")
        else:
            print(f"{args.role} connection code: {body.get('pairingCode')} (expires {body.get('expiresAt')})")
        return 0
    except Exception as error:
        print(f"Enrollment failed ({type(error).__name__}); private credential preserved for retry")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
