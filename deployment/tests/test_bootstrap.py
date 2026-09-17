import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bootstrap_camera import PAYLOAD_FILES, armed_cmdline, run_bootstrap
from install_camera import unit_text
from prepare_camera_bundle import create_bundle


class BootstrapTests(unittest.TestCase):
    def test_local_services_do_not_wait_for_network_online(self):
        for face in (False, True):
            unit = unit_text("existing-user", "/home/existing-user/cctvStreaming", "/home/existing-user/cctvStreaming/ai_env/bin/python", "fin_face.py" if face else "fin_camera.py", face)
            self.assertNotIn("network-online.target", unit)
            self.assertIn("After=network.target", unit)
            self.assertIn("UMask=0077", unit)

    def test_local_bundle_uses_reviewed_parameters_without_arming_source(self):
        with tempfile.TemporaryDirectory() as root:
            base = Path(root)
            repo = Path(__file__).resolve().parents[2]
            original = base / "cmdline.txt"
            data = b"console=tty1 root=PARTUUID=ad3978a6-02 rootfstype=ext4 rootwait\n"
            original.write_bytes(data)
            credential = base / "credentials.json"
            credential.write_text(json.dumps({"role": "CAMERA", "token": "test-only-" * 5, "serverUrl": "https://example.test"}))
            output = base / "bundle"
            manifest = create_bundle(repo, output, original, credential)
            self.assertEqual(original.read_bytes(), data)
            self.assertFalse((output / "cmdline.txt").exists())
            self.assertFalse(manifest["eventPhotosEnabled"])
            self.assertEqual(manifest["solenoidPin"], 24)
            self.assertNotIn("test-only-", (output / "door-bootstrap.json").read_text())
            for name, checksum in manifest["payloadSha256"].items():
                self.assertEqual(hashlib.sha256((output / "door-payload" / name).read_bytes()).hexdigest(), checksum)
            with self.assertRaises(ValueError):
                create_bundle(repo, output, original, credential)

    def prepare(self, root):
        boot = Path(root)
        original = b"console=serial0,115200 root=PARTUUID=ad3978a6-02 rootfstype=ext4 rootwait quiet\r\n"
        (boot / "door-original-cmdline.txt").write_bytes(original)
        (boot / "cmdline.txt").write_bytes(armed_cmdline(original))
        hashes = {}
        for name in PAYLOAD_FILES:
            path = boot / "door-payload" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("# test fixture only\n")
            hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
        manifest = {"version": 1, "originalCmdlineSha256": hashlib.sha256(original).hexdigest(),
                    "rootPartuuid": "ad3978a6-02", "payloadSha256": hashes,
                    "user": "existing-user", "projectDir": "/home/existing-user/cctvStreaming",
                    "python": "/home/existing-user/cctvStreaming/ai_env/bin/python",
                    "solenoidPin": 24, "faceThreshold": 80, "faceRequiredSuccesses": 2,
                    "disableServices": ["camera_stream.service"], "preparedCredential": False}
        (boot / "door-bootstrap.json").write_text(json.dumps(manifest))
        return boot, original

    def test_normal_boot_restored_before_installer_failure(self):
        with tempfile.TemporaryDirectory() as root:
            boot, original = self.prepare(root)
            def fail(command, **kwargs):
                self.assertEqual((boot / "cmdline.txt").read_bytes(), original)
                raise subprocess.CalledProcessError(1, "mock-install")
            self.assertEqual(run_bootstrap(boot, fail), 1)
            self.assertEqual((boot / "cmdline.txt").read_bytes(), original)
            self.assertEqual(json.loads((boot / "door-install-result.json").read_text())["status"], "failed")

    def test_corrupt_payload_restores_boot_but_never_installs(self):
        with tempfile.TemporaryDirectory() as root:
            boot, original = self.prepare(root)
            (boot / "door-payload" / PAYLOAD_FILES[0]).write_text("modified")
            runner = Mock()
            self.assertEqual(run_bootstrap(boot, runner), 1)
            runner.assert_not_called()
            self.assertEqual((boot / "cmdline.txt").read_bytes(), original)

    def test_changed_unrelated_boot_options_are_not_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            boot, original = self.prepare(root)
            changed = armed_cmdline(original).rstrip() + b" unrelated=keep\n"
            (boot / "cmdline.txt").write_bytes(changed)
            runner = Mock()
            self.assertEqual(run_bootstrap(boot, runner), 1)
            self.assertEqual((boot / "cmdline.txt").read_bytes(), changed)
            runner.assert_not_called()

    def test_reviewed_card_gpio_and_thresholds_are_passed_explicitly(self):
        with tempfile.TemporaryDirectory() as root:
            boot, original = self.prepare(root)
            runner = Mock()
            self.assertEqual(run_bootstrap(boot, runner), 0)
            command = runner.call_args.args[0]
            self.assertEqual(command[command.index("--solenoid-pin") + 1], "24")
            self.assertEqual(command[command.index("--face-threshold") + 1], "80")
            self.assertEqual(command[command.index("--face-required-successes") + 1], "2")
            self.assertEqual(command[command.index("--disable-service") + 1], "camera_stream.service")
            self.assertNotIn("--start", command)
            self.assertNotIn("--event-photos-enabled", command)


if __name__ == "__main__":
    unittest.main()
