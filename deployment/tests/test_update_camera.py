import hashlib
import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("update_camera", Path(__file__).resolve().parents[1] / "update_camera.py")
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)


class UpdateTests(unittest.TestCase):
    def test_rejects_changed_or_unexpected_source_before_compile(self):
        content = b"value = 1\n"
        with patch.dict(update.SOURCE_HASHES, {"fin_camera.py": hashlib.sha256(content).hexdigest()}, clear=True):
            update.verify_payload("fin_camera.py", content)
            with self.assertRaises(ValueError):
                update.verify_payload("fin_camera.py", b"value = 2\n")
            with self.assertRaises(ValueError):
                update.verify_payload("credentials.json", content)

    def test_even_matching_hash_does_not_accept_invalid_python(self):
        content = b"if broken\n"
        with patch.dict(update.SOURCE_HASHES, {"fin_camera.py": hashlib.sha256(content).hexdigest()}, clear=True):
            with self.assertRaises(SyntaxError):
                update.verify_payload("fin_camera.py", content)

    def test_no_network_for_unsealed_manifest(self):
        with patch.object(update, "SOURCE_REVISION", "PENDING"), patch.object(update, "urlopen") as request:
            with self.assertRaises(ValueError):
                update.fetch_payload(Path("unused"))
            request.assert_not_called()

    def test_requirements_rejects_alternate_index_and_unpinned_packages(self):
        for content in (b'aiortc\n', b'--index-url https://invalid.example\n', b'av>=17\n'):
            with patch.dict(update.SOURCE_HASHES, {"requirements-webrtc.txt": hashlib.sha256(content).hexdigest()}, clear=True):
                with self.assertRaises(ValueError):
                    update.verify_payload("requirements-webrtc.txt", content)
        content = b'# tested dependencies\naiortc==1.15.0\nav==17.1.0\n'
        with patch.dict(update.SOURCE_HASHES, {"requirements-webrtc.txt": hashlib.sha256(content).hexdigest()}, clear=True):
            update.verify_payload("requirements-webrtc.txt", content)

    def exercise_update(self, camera_ready, previous_override=False):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        project, payload, backup = root / "project", root / "payload", root / "backup"
        project.mkdir()
        payload.mkdir()
        for name in update.FILES:
            if name in update.EXISTING_FILES:
                (project / name).write_text("old source", encoding="utf-8")
            (payload / name).write_text("new source", encoding="utf-8")
        override = root / 'units' / '30-webrtc.conf'
        if previous_override:
            override.parent.mkdir()
            override.write_text('previous camera runtime', encoding='utf-8')
        runtime = root / 'new-rtc-env' / 'bin' / 'python'
        config = root / "camera.env"
        config.write_text("SOLENOID_PIN=24\nEVENT_PHOTOS_ENABLED=false\n", encoding="utf-8")
        for name in ("device_state", "trainer", "facedata", "ai_env"):
            (project / name).mkdir()
            (project / name / "preserved").write_text("untouched", encoding="utf-8")
        calls = []

        def run(*args, **kwargs):
            calls.append(args)
            return SimpleNamespace(returncode=0, stdout="")

        def replace(source, target, *args):
            target.write_bytes(source.read_bytes())

        with patch.object(update, "CONFIG", config), patch.object(update, "OVERRIDE", override), \
                patch.object(update, "command", side_effect=run), \
                patch.object(update, "replace_source", side_effect=replace), \
                patch.object(update, "local_camera_ready", return_value=camera_ready), \
                patch.object(update, "plain_path", return_value=True), patch.object(update, "sync_directory"), \
                patch.object(update.os, "sync", create=True), patch.object(update.time, "sleep"):
            if camera_ready:
                update.apply_update(project, payload, 1000, 1000, backup, runtime)
            else:
                with self.assertRaises(RuntimeError):
                    update.apply_update(project, payload, 1000, 1000, backup, runtime)
        for name in update.FILES:
            if camera_ready or name in update.EXISTING_FILES:
                self.assertEqual((project / name).read_text(), "new source" if camera_ready else "old source")
            else:
                self.assertFalse((project / name).exists())
            if name in update.EXISTING_FILES:
                self.assertEqual((backup / name).read_text(), "old source")
        if camera_ready:
            self.assertIn(str(runtime), override.read_text())
            self.assertNotIn('ai_env', override.read_text())
        elif previous_override:
            self.assertEqual(override.read_text(), 'previous camera runtime')
        else:
            self.assertFalse(override.exists())
        self.assertEqual(config.read_text(), "SOLENOID_PIN=24\nEVENT_PHOTOS_ENABLED=false\n")
        for name in ("device_state", "trainer", "facedata", "ai_env"):
            self.assertEqual((project / name / "preserved").read_text(), "untouched")
        self.assertIn(("systemctl", "stop", *update.SERVICES), calls)
        self.assertIn(("systemctl", "daemon-reload"), calls)
        self.assertEqual(calls[-1], ("systemctl", "start", *update.SERVICES))

    def test_success_preserves_credentials_models_and_config(self):
        self.exercise_update(True)

    def test_failed_camera_start_restores_sources_and_services(self):
        self.exercise_update(False)

    def test_failed_upgrade_restores_existing_camera_runtime_override(self):
        self.exercise_update(False, previous_override=True)


if __name__ == "__main__":
    unittest.main()
