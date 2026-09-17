"""Update an already installed Pi A; preserve pairing, models and camera.env.

Verifies pinned sources and prepares a separate WebRTC runtime before stopping
services. Restores sources and camera runtime if local startup fails. The face
virtualenv, network settings, pairing, trained models and GPIO are preserved.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.request import Request, urlopen


# Filled from the reviewed, tested source commit before publishing this updater.
SOURCE_REVISION = "8c4935b40e68dfc958f96f10f761a66debb08a98"
SOURCE_HASHES = {
    "pi_runtime.py": "3242b276c9917f74d2fd21cdb7a5c3ca4fedd05226963739e57150f94f5c0932",
    "fin_face.py": "1a495cedd5d1239862aa86d089f2d119420f9bad197c1f537ffd9415a306aaa6",
    "webrtc_transport.py": "6e8ff1a527dce35c3efc65e6e01b06294d1fb75455f7e8697243b43e473069a8",
    "requirements-webrtc.txt": "c562afda3e1c0f2237110321939bd3470c19a0863ba06eecf85fa4abefeb9b8a",
    "fin_camera.py": "f853e36e7f3911a74f5f53483832fe9e3e675ce4027a68f62581a0b59f3e1023"
}
# Install shared helpers before the camera entry point. A missing aiortc runtime
# leaves local capture running; code + service changes are not power-loss atomic.
FILES = ("pi_runtime.py", "fin_face.py", "webrtc_transport.py", "requirements-webrtc.txt", "fin_camera.py")
EXISTING_FILES = ("pi_runtime.py", "fin_face.py", "fin_camera.py", "device_pairing.py")
SERVICES = ("idla-camera.service", "idla-face.service")
CONFIG = Path("/etc/idontlivealone/camera.env")
BACKUPS = Path("/var/backups/idontlivealone")
OVERRIDE = Path("/etc/systemd/system/idla-camera.service.d/30-webrtc.conf")
RUNTIMES = Path("/opt/idontlivealone")


def command(*args, check=True):
    return subprocess.run(args, check=check, capture_output=True, text=True)


def verify_payload(name, content):
    if name not in FILES or len(content) > 1024 * 1024:
        raise ValueError("Unexpected update source")
    if hashlib.sha256(content).hexdigest() != SOURCE_HASHES.get(name):
        raise ValueError("Downloaded source hash does not match the reviewed version")
    text = content.decode("utf-8")
    if name.endswith(".py"):
        compile(text, name, "exec")
    else:
        packages = [line.strip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]
        if not packages or any(not re.fullmatch(r"[A-Za-z0-9_.-]+==[A-Za-z0-9_.+!-]+", item) for item in packages):
            raise ValueError("Runtime requirements must contain exact package versions")


def fetch_payload(destination):
    if not re.fullmatch(r"[0-9a-f]{40}", SOURCE_REVISION) or set(SOURCE_HASHES) != set(FILES):
        raise ValueError("This updater has no verified release manifest")
    for name in FILES:
        url = f"https://raw.githubusercontent.com/yeonh0115/IDontLIveAlone/{SOURCE_REVISION}/raspberrypi/{name}"
        with urlopen(Request(url, headers={"User-Agent": "IDLA-camera-updater"}), timeout=20) as response:
            if response.status != 200:
                raise ValueError("Source download failed")
            content = response.read(1024 * 1024 + 1)
        verify_payload(name, content)
        (destination / name).write_bytes(content)


def plain_path(path):
    return path.is_absolute() and re.fullmatch(r"/[A-Za-z0-9_./-]+", str(path)) and ".." not in path.parts and path.resolve() == path


def installed_project():
    projects, users = [], []
    for service in SERVICES:
        projects.append(command("systemctl", "show", "--value", "-p", "WorkingDirectory", service).stdout.strip())
        users.append(command("systemctl", "show", "--value", "-p", "User", service).stdout.strip())
    if projects[0] != projects[1] or users[0] != users[1] or not users[0] or users[0] == "root":
        raise ValueError("Existing camera and face service configuration does not match")
    project = Path(projects[0])
    if not plain_path(project) or not project.is_dir():
        raise ValueError("Existing project directory is missing or uses symlink aliases")
    if not str(project).startswith("/home/") or len(project.parts) < 4:
        raise ValueError("Unexpected project directory")
    for name in EXISTING_FILES:
        if not (project / name).is_file() or (project / name).is_symlink():
            raise ValueError("Existing source must be a regular file")
    for name in FILES:
        target = project / name
        if target.is_symlink() or (target.exists() and not target.is_file()):
            raise ValueError("Update targets must be regular files")
    if not CONFIG.is_file() or CONFIG.is_symlink():
        raise ValueError("Existing camera environment is missing")
    if not plain_path(OVERRIDE.parent) or OVERRIDE.is_symlink() or (OVERRIDE.exists() and not OVERRIDE.is_file()):
        raise ValueError("Invalid camera service override path")
    python = project / "ai_env" / "bin" / "python"
    if not python.is_file():
        raise ValueError("Existing virtual environment is missing")
    import pwd
    account = pwd.getpwnam(users[0])
    if account.pw_uid == 0:
        raise ValueError("Service account must be non-root")
    return project, python, account.pw_uid, account.pw_gid


def prepare_runtime(python, payload):
    """Install only into a new directory; the working face venv is untouched."""
    if not plain_path(RUNTIMES):
        raise ValueError("Runtime directory may not use symlink aliases")
    RUNTIMES.mkdir(parents=True, exist_ok=True, mode=0o755)
    if RUNTIMES.stat().st_uid != 0 or RUNTIMES.stat().st_mode & 0o022:
        raise ValueError("Runtime directory must be writable only by root")
    runtime = Path(tempfile.mkdtemp(prefix="rtc-" + SOURCE_REVISION[:12] + "-", dir=RUNTIMES))
    os.chmod(runtime, 0o755)
    print("Preparing the separate WebRTC runtime; this may take several minutes...", flush=True)
    command(str(python), "-m", "venv", str(runtime))
    rtc_python = runtime / "bin" / "python"
    # Ignore user pip configuration and alternate indexes. Missing binary wheels
    # fail before services stop, instead of compiling FFmpeg on the Pi.
    command(str(rtc_python), "-m", "pip", "--isolated", "--disable-pip-version-check", "install",
            "--no-input", "--no-cache-dir", "--only-binary=:all:", "--index-url", "https://pypi.org/simple",
            "--timeout", "30", "--retries", "2", "-r", str(payload / "requirements-webrtc.txt"))
    command(str(rtc_python), "-m", "pip", "--isolated", "check")
    command(str(rtc_python), "-c", "import aiortc,av,flask,requests; assert aiortc.__version__=='1.15.0'; av.CodecContext.create('mjpeg','r'); av.CodecContext.create('libvpx','w'); av.CodecContext.create('libx264','w')")
    (runtime / "idla-source-revision").write_text(SOURCE_REVISION + "\n", encoding="ascii")
    os.sync()
    return rtc_python


def replace_source(source, target, uid, gid, mode=0o644):
    descriptor, name = tempfile.mkstemp(prefix=".idla-update-", dir=target.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(source.read_bytes())
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, mode)
        os.chown(temporary, uid, gid)
        os.replace(temporary, target)
        sync_directory(target.parent)
    finally:
        temporary.unlink(missing_ok=True)


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def local_camera_ready():
    if any(command("systemctl", "is-active", "--quiet", service, check=False).returncode for service in SERVICES):
        return False
    try:
        with urlopen("http://127.0.0.1:5002/snapshot", timeout=2) as response:
            # Check framing without writing camera images to disk.
            jpeg = response.read(512 * 1024 + 1)
            if response.status != 200 or not (4 < len(jpeg) <= 512 * 1024) or not jpeg.startswith(b"\xff\xd8") or not jpeg.endswith(b"\xff\xd9"):
                return False
        with urlopen("http://127.0.0.1:5002/video_status", timeout=2) as response:
            state = json.loads(response.read(8193))
            return response.status == 200 and state.get("transport") == "webrtc" and state.get("runtimeAvailable") is True
    except Exception:
        return False


def override_text(project, rtc_python):
    if not plain_path(project) or not plain_path(rtc_python.parent):
        raise ValueError("Unsafe systemd executable path")
    return f"[Service]\nExecStart=\nExecStart={rtc_python} {project}/fin_camera.py\n"


def apply_update(project, payload, uid, gid, backup, rtc_python):
    backup.mkdir(parents=True, mode=0o700)
    os.chmod(backup, 0o700)
    previous_active = [name for name in SERVICES if command("systemctl", "is-active", "--quiet", name, check=False).returncode == 0]
    targets = {name: project / name for name in FILES}
    targets["camera-unit-override.conf"] = OVERRIDE
    metadata = {}
    for name, original in targets.items():
        if original.exists():
            stat = original.stat()
            metadata[name] = (stat.st_uid, stat.st_gid, stat.st_mode & 0o777)
            shutil.copy2(original, backup / name)
        else:
            metadata[name] = None
    # A private reference copy only. The updater never overwrites this config.
    shutil.copy2(CONFIG, backup / "camera.env")
    (backup / "restore-info.json").write_text(json.dumps({"project": str(project), "sourceRevision": SOURCE_REVISION,
        "cameraRuntime": str(rtc_python), "previousActive": previous_active, "metadata": metadata}, indent=2), encoding="utf-8")
    prepared_override = payload / "camera-unit-override.conf"
    prepared_override.write_text(override_text(project, rtc_python), encoding="utf-8")
    os.sync()  # Make the complete recovery copy durable before replacing anything.
    changed = []
    try:
        command("systemctl", "stop", *SERVICES)
        for name in FILES:
            changed.append(name)
            replace_source(payload / name, project / name, uid, gid)
        OVERRIDE.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
        changed.append("camera-unit-override.conf")
        replace_source(prepared_override, OVERRIDE, 0, 0)
        os.sync()
        command("systemctl", "daemon-reload")
        command("systemctl", "start", *SERVICES)
        for _ in range(30):
            if local_camera_ready():
                return
            time.sleep(1)
        raise RuntimeError("Updated local camera did not become ready")
    except BaseException:
        print("Startup failed; restoring previous sources and camera runtime.", flush=True)
        command("systemctl", "stop", *SERVICES, check=False)
        for name in reversed(changed):
            if metadata[name] is None:
                targets[name].unlink(missing_ok=True)
                sync_directory(targets[name].parent)
            else:
                replace_source(backup / name, targets[name], *metadata[name])
        os.sync()
        command("systemctl", "daemon-reload")
        if previous_active:
            command("systemctl", "start", *previous_active)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Back up, replace verified sources and restart existing services")
    args = parser.parse_args()
    if sys.platform != "linux" or os.geteuid() != 0:
        raise RuntimeError("Run this updater with sudo python3 on the already installed Raspberry Pi")
    project, python, uid, gid = installed_project()
    print(f"Camera update: {project}", flush=True)
    print("Pairing, trained faces, face ai_env, GPIO and Wi-Fi settings are preserved.", flush=True)
    with tempfile.TemporaryDirectory(prefix="idla-camera-update-") as work:
        payload = Path(work)
        print("Downloading and checking the reviewed source version...", flush=True)
        fetch_payload(payload)
        # Compile with the Pi's actual interpreter, without importing hardware code.
        command(str(python), "-c", "import pathlib,sys; [compile(p.read_bytes(), str(p), 'exec') for p in pathlib.Path(sys.argv[1]).glob('*.py')]", str(payload))
        if not args.apply:
            print("Source validation passed; use --apply to prepare the runtime and install.")
            return 0
        rtc_python = prepare_runtime(python, payload)
        backup = BACKUPS / ("webrtc-update-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ"))
        since = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
        apply_update(project, payload, uid, gid, backup, rtc_python)
    print(f"LOCAL CAMERA + WEBRTC RUNTIME OK. Previous sources: {backup}", flush=True)
    # Check a fixed, non-sensitive success marker without echoing arbitrary logs.
    for _ in range(20):
        logs = command("journalctl", "-u", SERVICES[0], "--since", since, "--no-pager", "-o", "cat", check=False).stdout
        if "[webrtc] signaling ready" in logs:
            print("SIGNALING OK. Open the updated app; actual direct video still needs an LTE test.")
            return 0
        time.sleep(1)
    print("Local update finished. Signaling is not confirmed yet; keep Pi powered on and check its network connection.")
    return 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        # Network exception strings may contain proxy credentials; never echo them.
        print(f"UPDATE STOPPED ({type(error).__name__}). Keep the backup and report this line.", file=sys.stderr)
        raise SystemExit(1)
