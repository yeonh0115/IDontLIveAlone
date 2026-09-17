"""Reviewed one-shot wrapper for the existing Pi A card; never arms a boot itself."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


BOOT_OPTIONS = (
    "systemd.run=/boot/firmware/door-bootstrap.sh",
    "systemd.run_success_action=reboot",
    "systemd.run_failure_action=poweroff",
    "systemd.unit=kernel-command-line.target",
)
PAYLOAD_FILES = (
    "deployment/install_camera.py", "deployment/bootstrap_camera.py",
    "raspberrypi/fin_camera.py", "raspberrypi/fin_face.py", "raspberrypi/pi_runtime.py",
    "raspberrypi/network_diagnostics.py", "device_client/device_pairing.py",
)


def armed_cmdline(original):
    """Pure builder for the coordinator; callers back up and write the real card."""
    text = original.decode("utf-8")
    if len(text.strip().splitlines()) != 1 or "\x00" in text:
        raise ValueError("Expected one original kernel command line")
    words = text.split()
    if any(word.startswith(("systemd.run=", "systemd.run_success_action=", "systemd.run_failure_action=", "systemd.unit=")) for word in words):
        raise ValueError("Existing systemd boot override requires manual review")
    return (text.rstrip("\r\n") + " " + " ".join(BOOT_OPTIONS) + "\n").encode("utf-8")


def replace_synced(path, data):
    temporary = path.with_name(path.name + ".installing")
    with temporary.open("wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)
    if hasattr(os, "sync"):
        os.sync()


def restore_cmdline(boot, manifest):
    original = (boot / "door-original-cmdline.txt").read_bytes()
    if hashlib.sha256(original).hexdigest() != manifest.get("originalCmdlineSha256"):
        raise ValueError("Original command line backup checksum differs")
    if "root=PARTUUID=" + manifest["rootPartuuid"] not in original.decode("utf-8").split():
        raise ValueError("Original command line does not identify the reviewed root partition")
    current = (boot / "cmdline.txt").read_bytes()
    if current.strip() not in (original.strip(), armed_cmdline(original).strip()):
        raise ValueError("Command line changed after preparation; refusing to overwrite unrelated options")
    replace_synced(boot / "cmdline.txt", original)


def run_bootstrap(boot, runner=subprocess.run):
    boot = Path(boot)
    result = {"status": "failed", "finishedAt": None}
    try:
        manifest = json.loads((boot / "door-bootstrap.json").read_text(encoding="utf-8"))
        if manifest.get("version") != 1:
            raise ValueError("Unknown bootstrap manifest version")
        # Restore normal boot before importing modules, installing or checking the network.
        # On any later failure, the next power-on follows the original boot configuration.
        restore_cmdline(boot, manifest)
        payload = boot / "door-payload"
        hashes = manifest.get("payloadSha256", {})
        for name in PAYLOAD_FILES:
            path = payload / name
            if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != hashes.get(name):
                raise ValueError("Incomplete or changed bootstrap payload")
        script = payload / "deployment" / "install_camera.py"
        command = ["/usr/bin/python3", str(script), "--payload", str(payload),
                   "--user", manifest["user"], "--project-dir", manifest["projectDir"],
                   "--python", manifest["python"], "--solenoid-pin", str(manifest["solenoidPin"]),
                   "--face-threshold", str(manifest["faceThreshold"]),
                   "--face-required-successes", str(manifest["faceRequiredSuccesses"])]
        credential = boot / "door-credentials.json"
        if manifest.get("preparedCredential"):
            if not credential.is_file() or credential.is_symlink():
                raise ValueError("Prepared credential is missing")
            command += ["--credential", str(credential)]
        for service in manifest.get("disableServices", []):
            command += ["--disable-service", service]
        if manifest.get("eventPhotosEnabled") is True:
            command.append("--event-photos-enabled")
        command.append("--apply")
        runner(command, check=True)
        if manifest.get("preparedCredential"):
            installed = Path(manifest["projectDir"]) / "device_state" / "credentials.json"
            if json.loads(credential.read_text(encoding="utf-8")) != json.loads(installed.read_text(encoding="utf-8")):
                raise ValueError("Installed credential verification failed; original retained")
            # Delete the temporary FAT copy only after confirming the private Linux copy.
            credential.unlink()
        result["status"] = "success"
        result["services"] = ["idla-camera.service", "idla-face.service"]
        return 0
    except Exception as error:
        # Error messages can contain a secret value from a malformed input; class only.
        result["errorType"] = type(error).__name__
        print(f"[bootstrap] {type(error).__name__}; stopped, inspect private install backup", file=sys.stderr)
        return 1
    finally:
        result["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        replace_synced(boot / "door-install-result.json", json.dumps(result).encode("utf-8"))


if __name__ == "__main__":
    raise SystemExit(run_bootstrap(Path("/boot/firmware")))
