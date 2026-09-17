"""Build a local, private Pi A bundle. This tool never writes or arms an SD card."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
from urllib.parse import urlsplit

from bootstrap_camera import PAYLOAD_FILES, armed_cmdline


def create_bundle(repo, output, original_cmdline, credential_path):
    repo, output = Path(repo).resolve(), Path(output).resolve()
    # This Windows workflow creates its staging bundle on the repository's PC disk.
    # F:/bootfs is read only input; the coordinator performs the reviewed final copy.
    if os.name == "nt" and output.drive.lower() != repo.drive.lower():
        raise ValueError("Stage the bundle on the same PC volume as the repository, not the SD card")
    if output == Path(output.anchor) or output == repo or (output.exists() and any(output.iterdir())):
        raise ValueError("Choose a new empty private output directory")
    original = Path(original_cmdline).read_bytes()
    armed = armed_cmdline(original)
    root_id = "ad3978a6-02"
    if "root=PARTUUID=" + root_id not in original.decode("utf-8").split():
        raise ValueError("This bundle profile is only for the reviewed Pi A root partition")
    credential = json.loads(Path(credential_path).read_text(encoding="utf-8"))
    token = credential.get("token")
    server = urlsplit(credential.get("serverUrl", ""))
    if (credential.get("role") != "CAMERA" or not isinstance(token, str) or len(token) < 32
            or any(char.isspace() for char in token) or server.scheme != "https" or not server.hostname
            or server.username or server.password or server.query or server.fragment or server.path not in ("", "/")):
        raise ValueError("Use the prepared private CAMERA credential for the HTTPS server")
    for name in PAYLOAD_FILES:
        path = repo / name
        if not path.is_file() or path.is_symlink():
            raise ValueError("Missing or symlinked source payload")
        compile(path.read_text(encoding="utf-8"), str(path), "exec")
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    hashes = {}
    for name in PAYLOAD_FILES:
        target = output / "door-payload" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(repo / name, target)
        hashes[name] = hashlib.sha256(target.read_bytes()).hexdigest()
    shell = (repo / "deployment" / "door-bootstrap.sh").read_bytes().replace(b"\r\n", b"\n")
    (output / "door-bootstrap.sh").write_bytes(shell)
    (output / "door-original-cmdline.txt").write_bytes(original)
    (output / "door-armed-cmdline.txt").write_bytes(armed)
    credential_target = output / "door-credentials.json"
    descriptor = os.open(credential_target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(credential, handle)
        handle.flush()
        os.fsync(handle.fileno())
    manifest = {
        "version": 1, "originalCmdlineSha256": hashlib.sha256(original).hexdigest(),
        "rootPartuuid": root_id, "payloadSha256": hashes,
        "user": "yeonh0115", "projectDir": "/home/yeonh0115/cctvStreaming",
        "python": "/home/yeonh0115/cctvStreaming/ai_env/bin/python",
        "solenoidPin": 24, "faceThreshold": 80, "faceRequiredSuccesses": 2,
        "disableServices": ["camera_stream.service"],
        "preparedCredential": True, "eventPhotosEnabled": False,
    }
    (output / "door-bootstrap.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="New private folder on the PC; do not use the SD card")
    parser.add_argument("--original-cmdline", required=True, help="Unmodified cmdline.txt or its exact private backup")
    parser.add_argument("--credential", required=True, help="Prepared CAMERA credentials.json, never printed")
    args = parser.parse_args()
    try:
        repo = Path(__file__).resolve().parents[1]
        create_bundle(repo, args.output, args.original_cmdline, args.credential)
        print("Private Pi A bundle created; GPIO24/threshold80/2 matches, event photos disabled. SD card remains unmodified.")
        return 0
    except Exception as error:
        print(f"Bundle preparation stopped ({type(error).__name__}); private inputs preserved", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
