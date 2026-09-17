"""Non-destructive Pi A installer. Run on Linux only after inspecting/backing up the SD.

This file never edits cmdline.txt, cloud-init, networking, accounts or the virtualenv.
Default mode prints a plan; --apply installs and enables services for the next boot.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


FILES = ("fin_camera.py", "fin_face.py", "pi_runtime.py", "network_diagnostics.py")


def require_plain_path(value):
    path = Path(value)
    if not path.is_absolute() or not re.fullmatch(r"/[A-Za-z0-9_./-]+", str(path)) or ".." in path.parts:
        raise ValueError("Installer paths must be absolute Linux paths without spaces or traversal")
    if path.resolve() != path:
        raise ValueError("Installer target may not use symlink aliases")
    return path


def unit_text(user, project, python_path, script, after_camera=False):
    # Local capture/GPIO recognition must boot even when Wi-Fi/Render is offline.
    dependencies = "network.target" + (" idla-camera.service" if after_camera else "")
    return f"""[Unit]
Description=I Don't Live Alone {'face recognition' if after_camera else 'camera'}
After={dependencies}
StartLimitIntervalSec=0

[Service]
Type=simple
User={user}
WorkingDirectory={project}
EnvironmentFile=/etc/idontlivealone/camera.env
Environment=PYTHONUNBUFFERED=1
UMask=0077
ExecStart={python_path} {project}/{script}
Restart=on-failure
RestartSec=5
TimeoutStopSec=15

[Install]
WantedBy=multi-user.target
"""


def main():
    import pwd
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload", required=True, help="Repo-shaped directory containing raspberrypi/ and device_client/")
    parser.add_argument("--user", required=True, help="Existing Linux username, verified from the SD")
    parser.add_argument("--project-dir", required=True, help="Existing directory holding trainer/ and facedata/")
    parser.add_argument("--python", required=True, help="Existing ai_env/bin/python; the environment is preserved")
    parser.add_argument("--credential", help="Prepared CAMERA credentials.json; never printed")
    parser.add_argument("--solenoid-pin", type=int, default=23)
    parser.add_argument("--face-threshold", type=float, default=85)
    parser.add_argument("--face-required-successes", type=int, default=4)
    parser.add_argument("--event-photos-enabled", action="store_true", help="Opt in only after explicit user authorization; default is no event photos")
    parser.add_argument("--disable-service", action="append", default=[], help="Explicitly reviewed old camera/face unit to disable")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    payload = require_plain_path(args.payload)
    project = require_plain_path(args.project_dir)
    python_path = Path(args.python)
    # A venv's python is normally a symlink, so it is deliberately not rejected here.
    if not python_path.is_absolute() or not re.fullmatch(r"/[A-Za-z0-9_./-]+", str(python_path)) or ".." in python_path.parts:
        raise ValueError("Python must be an absolute existing virtualenv executable")
    account = pwd.getpwnam(args.user)
    if account.pw_uid == 0 or not project.is_dir() or not python_path.is_file():
        raise ValueError("Existing non-root account, project directory and Python executable are required")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.user):
        raise ValueError("Invalid Linux username")
    sources = {name: payload / "raspberrypi" / name for name in FILES}
    sources["device_pairing.py"] = payload / "device_client" / "device_pairing.py"
    if not all(path.is_file() and not path.is_symlink() for path in sources.values()):
        raise ValueError("Payload is incomplete or contains symlinked source files")
    for path in sources.values():
        compile(path.read_text(encoding="utf-8"), str(path), "exec")
    if (project / "trainer.yml").is_file() and not (project / "trainer" / "trainer.yml").is_file():
        raise ValueError("Legacy trainer.yml is in the project root; resolve its model/map migration explicitly first")
    for service in args.disable_service:
        if not re.fullmatch(r"[A-Za-z0-9_.@-]+\.service", service) or service in ("idla-camera.service", "idla-face.service"):
            raise ValueError("Only explicitly reviewed old camera/face service names may be disabled")
    credential = None
    if args.credential:
        credential = json.loads(Path(args.credential).read_text(encoding="utf-8"))
        if credential.get("role") != "CAMERA" or not isinstance(credential.get("token"), str) or len(credential["token"]) < 32:
            raise ValueError("Prepared credential is not a valid CAMERA identity")
    state = project / "device_state"
    sys.path.insert(0, str(payload / "raspberrypi"))
    from pi_runtime import Settings
    Settings({"SOLENOID_PIN": str(args.solenoid_pin), "FACE_THRESHOLD": str(args.face_threshold), "FACE_REQUIRED_SUCCESSES": str(args.face_required_successes)})
    server = credential.get("serverUrl") if credential else "https://idontlivealone.onrender.com"
    if not isinstance(server, str) or not re.fullmatch(r"https://[A-Za-z0-9.-]+(?::[0-9]+)?", server):
        raise ValueError("Prepared server URL must be an HTTPS origin")
    if credential and (state / "credentials.json").exists():
        saved = json.loads((state / "credentials.json").read_text(encoding="utf-8"))
        if saved != credential:
            raise ValueError("Existing camera credentials differ; never replace a paired identity automatically")
    print(f"Pi A install plan: existing user {args.user}, directory {project}, Python {python_path}")
    print("Preserve OS, ai_env, model, facedata, GPIO values, accounts and network configuration.")
    print("Back up replaced sources/config/services and trainer/facedata before installing; enable two services for next boot.")
    if not args.apply:
        return 0
    if os.geteuid() != 0:
        raise PermissionError("--apply requires root on the actual Pi")
    # Imports only: no capture, network, GPIO line requests or package installation.
    subprocess.run([str(python_path), "-c", "import cv2,numpy,requests,websocket,flask,gpiod; assert hasattr(cv2,'face') and hasattr(gpiod,'request_lines')"], check=True)
    backup = Path("/var/backups/idontlivealone") / dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    backup.mkdir(parents=True, mode=0o700)
    replaced = [project / name for name in sources]
    replaced += [project / "trainer", project / "facedata", state, Path("/etc/idontlivealone/camera.env")]
    replaced += [Path("/etc/systemd/system") / name for name in ("idla-camera.service", "idla-face.service", *args.disable_service)]
    for path in replaced:
        if path.exists() or path.is_symlink():
            destination = backup / path.relative_to("/")
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            if path.is_dir() and not path.is_symlink():
                shutil.copytree(path, destination, symlinks=True)
            else:
                shutil.copy2(path, destination, follow_symlinks=False)
    # Capture old enablement state so recovery does not depend on remembered settings.
    previous_units = {}
    for service in args.disable_service:
        result = subprocess.run(["systemctl", "is-enabled", service], capture_output=True, text=True)
        previous_units[service] = result.stdout.strip()
    (backup / "old-service-state.json").write_text(json.dumps(previous_units), encoding="utf-8")
    for name, source in sources.items():
        target = project / name
        temporary = project / ("." + name + ".installing")
        shutil.copyfile(source, temporary)
        os.chmod(temporary, 0o644)
        os.chown(temporary, account.pw_uid, account.pw_gid)
        os.replace(temporary, target)
    state.mkdir(exist_ok=True, mode=0o700)
    os.chmod(state, 0o700)
    os.chown(state, account.pw_uid, account.pw_gid)
    if credential:
        sys.path.insert(0, str(project))
        from device_pairing import private_json
        private_json(state / "credentials.json", credential)
        os.chown(state / "credentials.json", account.pw_uid, account.pw_gid)
    config_dir = Path("/etc/idontlivealone")
    config_dir.mkdir(exist_ok=True, mode=0o755)
    config = config_dir / "camera.env"
    photos = "true" if args.event_photos_enabled else "false"
    config.write_text(f"DEVICE_PAIRING=1\nDEVICE_STATE_DIR={state}\nDEVICE_NAME=Door-camera\nRENDER_SERVER_URL={server}\nPI_DATA_DIR={project}\nSTREAM_URL=http://127.0.0.1:5002/video_feed\nSOLENOID_PIN={args.solenoid_pin}\nFACE_THRESHOLD={args.face_threshold}\nFACE_REQUIRED_SUCCESSES={args.face_required_successes}\nEVENT_PHOTOS_ENABLED={photos}\n", encoding="utf-8")
    os.chmod(config, 0o600)
    for name, script, face in (("idla-camera.service", "fin_camera.py", False), ("idla-face.service", "fin_face.py", True)):
        unit = Path("/etc/systemd/system") / name
        unit.write_text(unit_text(args.user, project, python_path, script, face), encoding="utf-8")
        os.chmod(unit, 0o644)
    subprocess.run(["systemctl", "daemon-reload"], check=True)
    for service in args.disable_service:
        subprocess.run(["systemctl", "disable", service], check=True)
    subprocess.run(["systemctl", "enable", "idla-camera.service", "idla-face.service"], check=True)
    os.sync()
    print(f"Installed for next normal boot. Private pre-install backup: {backup}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Pi A installation stopped ({type(error).__name__}); inspect local backup and configuration before retry", file=sys.stderr)
        raise SystemExit(1)
