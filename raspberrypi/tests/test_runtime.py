import importlib.util
import io
import json
import os
from pathlib import Path
import ssl
import sys
import tempfile
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pi_runtime import (
    LatestFrame, Settings, TaskState, atomic_json, commit_model_and_samples,
    event_log_id, prepare_model_baseline, recover_model_transaction,
    task_sample_directory, training_image_paths, upload_fields,
)

class StopLoop(BaseException):
    pass

def dependency_stubs():
    requests = ModuleType("requests")
    requests.RequestException = type("RequestException", (Exception,), {})
    requests.post = Mock()
    requests.Session = Mock()
    flask = ModuleType("flask")
    flask.Flask = lambda _: SimpleNamespace(route=lambda _: lambda function: function)
    flask.Response = Mock()
    flask.jsonify = lambda **values: values
    flask.request = SimpleNamespace(args={})
    websocket = ModuleType("websocket")
    websocket.create_connection = Mock()
    numpy = ModuleType("numpy")
    numpy.uint8, numpy.int32 = "uint8", "int32"
    numpy.frombuffer = lambda value, _: value
    numpy.array = lambda values, **_: list(values)
    cv2 = ModuleType("cv2")
    return {"requests": requests, "flask": flask, "websocket": websocket, "numpy": numpy, "cv2": cv2}

def load_program(filename, stubs):
    spec = importlib.util.spec_from_file_location("test_" + filename[:-3], ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, stubs), patch.dict("os.environ", {}, clear=True):
        spec.loader.exec_module(module)
    return module

class RuntimeTests(unittest.TestCase):
    def test_configuration_and_relative_download_url(self):
        config = Settings({}, ROOT)
        self.assertEqual(config.stream_url, "http://127.0.0.1:5002/video_feed")
        self.assertEqual(config.websocket_url, "wss://idontlivealone.onrender.com/ws/camera")
        self.assertEqual(config.task_params(), {"device_id": "door-camera-a"})
        config = Settings({"RENDER_SERVER_URL": "https://example.test/", "USER_NO": "42", "PI_DATA_DIR": "data"}, ROOT)
        self.assertEqual(config.api_url("/pictures/job/file.jpg"), "https://example.test/pictures/job/file.jpg")
        self.assertEqual(config.task_params()["user_no"], 42)
        self.assertEqual(config.data_dir, ROOT / "data")
        for invalid in ("0", "-1", "x", "1.2"):
            with self.assertRaises(ValueError):
                Settings({"USER_NO": invalid}, ROOT)

    def test_event_identity_and_no_implicit_account(self):
        self.assertEqual(upload_fields(42, "existing_log-7", "2026-09-16"), {"userNo": "42", "log_id": "existing_log-7", "date": "2026-09-16"})
        self.assertNotEqual(event_log_id(), event_log_id())
        for invalid in ("a.b", "../id", "x" * 101):
            with self.assertRaises(ValueError):
                event_log_id(invalid)
        with self.assertRaises(ValueError):
            upload_fields(None, "id", "2026-09-16")

    def test_two_consumers_and_stale_frame(self):
        clock = Mock(return_value=10)
        frames = LatestFrame(clock=clock)
        frames.put(b"frame")
        first = frames.get()
        self.assertEqual(frames.get(), first)
        clock.return_value = 16
        self.assertIsNone(frames.get()[0])
        frames.put(b"new")
        self.assertGreater(frames.get()[1], first[1])
        frames.clear()
        self.assertIsNone(frames.get()[0])

    def test_durable_result_requires_ack_and_retries_after_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            payload = {"task_id": "task-1", "type": "TRAIN", "device_id": "door-camera-a", "lease_token": "test-lease", "result": {"status": "success", "message": "done"}}
            state = TaskState(directory)
            path = state.queue(payload)
            restarted = TaskState(directory)
            for response in ((503, {}), (200, {"status": "error"}), (200, None)):
                self.assertEqual(restarted.deliver(path, lambda _: response), "retry")
                self.assertEqual(restarted.read(path), payload)
            with self.assertRaises(ConnectionError):
                restarted.deliver(path, Mock(side_effect=ConnectionError("offline")))
            self.assertTrue(path.exists())
            self.assertEqual(restarted.deliver(path, lambda _: (200, {"status": "success"})), "acknowledged")
            self.assertFalse(path.exists())

    def test_stale_lease_is_quarantined(self):
        with tempfile.TemporaryDirectory() as directory:
            state = TaskState(directory)
            path = state.queue({"task_id": "task-1", "lease_token": "old-test-lease", "result": {"status": "success"}})
            self.assertEqual(state.deliver(path, lambda _: (409, {})), "stale")
            self.assertEqual(state.pending(), [])
            self.assertTrue((state.quarantine / path.name).exists())

    def test_permanent_errors_preserve_original_but_forbidden_stays_pending(self):
        with tempfile.TemporaryDirectory() as directory:
            state = TaskState(directory)
            payload = {"task_id": "task-1", "lease_token": "test-lease", "result": {"status": "success"}}
            for status in (400, 404, 410, 422):
                path = state.queue(payload)
                self.assertEqual(state.deliver(path, lambda _: (status, {})), f"permanent:{status}")
                self.assertEqual(state.pending(), [])
                self.assertEqual(state.read(state.quarantine / path.name), payload)
            path = state.queue(payload)
            self.assertEqual(state.deliver(path, lambda _: (403, {})), "forbidden")
            self.assertTrue(path.exists())

    def test_legacy_baseline_is_frozen_before_new_models(self):
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / "trainer.yml"
            model.write_bytes(b"legacy-model")
            baseline = prepare_model_baseline(model)
            model.write_bytes(b"updated-model")
            self.assertEqual(prepare_model_baseline(model), baseline)
            self.assertEqual(baseline.read_bytes(), b"legacy-model")
            baseline.unlink()
            with self.assertRaises(RuntimeError):
                prepare_model_baseline(model)

    def test_legacy_and_task_dataset_ignore_uncommitted_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            task = task_sample_directory(root, 7, "job-1")
            task.mkdir(parents=True)
            (task / "0.jpg").write_bytes(b"task")
            (root / "7" / "0.jpg").write_bytes(b"legacy")
            (root / "7" / ".pending_test").mkdir()
            (root / "7" / ".pending_test" / "0.jpg").write_bytes(b"unfinished")
            self.assertEqual(len(list(training_image_paths(root))), 2)
            self.assertEqual(task_sample_directory(root, 7, "job-1"), task)

    def test_sample_publish_failure_restores_previous_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "trainer" / "trainer.yml"
            model.parent.mkdir()
            model.write_bytes(b"old-model")
            temporary = model.with_name("new.yml")
            temporary.write_bytes(b"new-model")
            staging = root / "facedata" / "7" / ".pending_test"
            staging.mkdir(parents=True)
            (staging / "0.jpg").write_bytes(b"face")
            destination = task_sample_directory(root / "facedata", 7, "task-1")
            real_replace = os.replace
            def fail_sample_rename(source, target):
                if Path(source) == staging and Path(target) == destination:
                    raise OSError("sample rename failed")
                return real_replace(source, target)
            with patch("pi_runtime.os.replace", side_effect=fail_sample_rename):
                with self.assertRaises(OSError):
                    commit_model_and_samples(model, temporary, staging, destination)
            self.assertEqual(model.read_bytes(), b"old-model")
            self.assertFalse(destination.exists())
            self.assertEqual(list(training_image_paths(root / "facedata")), [])
            self.assertFalse((model.parent / "task_model_transaction.json").exists())

    def test_interrupted_model_and_sample_commit_rolls_back_at_startup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "trainer" / "trainer.yml"
            model.parent.mkdir()
            model.write_bytes(b"new-model")
            (model.parent / "trainer.rollback.yml").write_bytes(b"old-model")
            staging = root / "facedata" / "7" / ".pending_test"
            destination = task_sample_directory(root / "facedata", 7, "task-1")
            destination.mkdir(parents=True)
            (destination / "0.jpg").write_bytes(b"face")
            atomic_json(model.parent / "task_model_transaction.json", {"had_model": True, "staging": str(staging), "destination": str(destination)})
            recover_model_transaction(model)
            self.assertEqual(model.read_bytes(), b"old-model")
            self.assertFalse(destination.exists())
            self.assertEqual(list(training_image_paths(root / "facedata")), [])

    def test_lease_loss_during_model_commit_rolls_back_model_and_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "trainer" / "trainer.yml"
            model.parent.mkdir()
            model.write_bytes(b"old-model")
            temporary = model.with_name("new.yml")
            temporary.write_bytes(b"new-model")
            staging = root / "facedata" / "7" / ".pending_test"
            staging.mkdir(parents=True)
            (staging / "0.jpg").write_bytes(b"face")
            destination = task_sample_directory(root / "facedata", 7, "task-1")
            check_owned = Mock(side_effect=[None, RuntimeError("lease expired during commit")])
            with self.assertRaises(RuntimeError):
                commit_model_and_samples(model, temporary, staging, destination, check_owned)
            self.assertEqual(model.read_bytes(), b"old-model")
            self.assertFalse(destination.exists())
            self.assertEqual(list(training_image_paths(root / "facedata")), [])

    def test_post_commit_cleanup_errors_do_not_report_training_failure(self):
        for failure in ("backup_unlink", "directory_sync"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                model = root / "trainer" / "trainer.yml"
                model.parent.mkdir()
                model.write_bytes(b"old-model")
                temporary = model.with_name("new.yml")
                temporary.write_bytes(b"new-model")
                staging = root / "facedata" / "7" / ".pending_test"
                staging.mkdir(parents=True)
                (staging / "0.jpg").write_bytes(b"face")
                destination = task_sample_directory(root / "facedata", 7, "task-1")
                backup = model.parent / "trainer.rollback.yml"
                journal = model.parent / "task_model_transaction.json"
                real_unlink = Path.unlink
                def unlink(path, *args, **kwargs):
                    if failure == "backup_unlink" and path == backup:
                        raise PermissionError("backup cleanup denied")
                    return real_unlink(path, *args, **kwargs)
                def sync(path):
                    if failure == "directory_sync" and Path(path) == model.parent and not journal.exists():
                        raise OSError("commit directory sync failed")
                with patch.object(Path, "unlink", unlink), patch("pi_runtime.sync_directory", side_effect=sync):
                    commit_model_and_samples(model, temporary, staging, destination)
                self.assertEqual(model.read_bytes(), b"new-model")
                self.assertTrue(destination.exists())
                self.assertFalse(journal.exists())
                self.assertTrue(backup.exists())

class CameraTests(unittest.TestCase):
    def setUp(self):
        self.stubs = dependency_stubs()
        self.camera = load_program("fin_camera.py", self.stubs)

    def test_camera_eof_clears_frame_and_restarts_process(self):
        processes = [SimpleNamespace(stdout=io.BytesIO(b"\xff\xd8image\xff\xd9"), poll=Mock(return_value=0), wait=Mock()) for _ in range(2)]
        popen = Mock(side_effect=processes)
        with patch.object(self.camera, "subprocess", SimpleNamespace(Popen=popen, PIPE=-1)), patch.object(self.camera, "time", SimpleNamespace(sleep=Mock(side_effect=[None, StopLoop()]))):
            with self.assertRaises(StopLoop):
                self.camera.capture_camera()
        self.assertEqual(popen.call_count, 2)
        self.assertIsNone(self.camera.frames.get()[0])
        for process in processes:
            self.assertTrue(process.stdout.closed)
            process.wait.assert_called_once()

    def test_websocket_requests_verified_tls(self):
        connect = self.stubs["websocket"].create_connection
        connect.side_effect = StopLoop()
        with self.assertRaises(StopLoop):
            self.camera.upload_to_cloud_websocket()
        options = connect.call_args.kwargs["sslopt"]
        self.assertEqual(options["cert_reqs"], ssl.CERT_REQUIRED)
        self.assertIs(options["check_hostname"], True)

    def test_trigger_keeps_log_id_and_uses_configured_account_only(self):
        self.camera.settings = Settings({"USER_NO": "42"}, ROOT)
        self.camera.frames.put(b"JPEG")
        self.camera.request.args = {"log_id": "same-log", "userNo": "999"}
        post = self.stubs["requests"].post
        post.return_value = SimpleNamespace(status_code=200, json=lambda: {"imageUrl": "/images/same-log.jpg"})
        response, code = self.camera.trigger_event()
        self.assertEqual(code, 200)
        self.assertEqual(response["log_id"], "same-log")
        self.assertEqual(post.call_args.kwargs["data"]["userNo"], "42")
        self.assertEqual(post.call_args.kwargs["data"]["log_id"], "same-log")
        self.assertIsNot(post.call_args.kwargs.get("verify", True), False)
        self.camera.settings = Settings({}, ROOT)
        post.reset_mock()
        self.assertEqual(self.camera.trigger_event()[1], 503)
        post.assert_not_called()

class FakeRecognizer:
    def __init__(self):
        self.labels = []
    def read(self, path):
        self.labels = json.loads(Path(path).read_text())["labels"]
    def update(self, images, labels):
        self.labels.extend(labels)
    def train(self, images, labels):
        self.labels = list(labels)
    def write(self, path):
        Path(path).write_text(json.dumps({"labels": self.labels}))

class TrainingReplayTests(unittest.TestCase):
    def test_missing_outbox_task_is_quarantined_and_next_task_is_processed(self):
        stubs = dependency_stubs()
        face = load_program("fin_face.py", stubs)
        class Lease:
            def __enter__(self):
                return self
            def __exit__(self, *_):
                pass
        face.LeaseHeartbeat = lambda _, **kwargs: Lease()
        face.stop_event = SimpleNamespace(is_set=lambda: False, wait=Mock(side_effect=StopLoop()))
        face.process_train = Mock(return_value={"status": "success", "message": "done"})
        with tempfile.TemporaryDirectory() as directory:
            face.settings = Settings({"PI_DATA_DIR": directory}, ROOT)
            state = TaskState(Path(directory) / "task_state")
            previous = {"task_id": "missing-task", "type": "TRAIN", "lease_token": "test-old-lease", "result": {"status": "success"}}
            path = state.queue(previous)
            task = {"task_id": "next-task", "type": "TRAIN", "lease_token": "test-new-lease"}
            session = SimpleNamespace(
                post=Mock(side_effect=[SimpleNamespace(status_code=404, json=lambda: {}), SimpleNamespace(status_code=200, json=lambda: {"status": "success"})]),
                get=Mock(return_value=SimpleNamespace(status_code=200, json=lambda: task, raise_for_status=lambda: None)),
            )
            context = Mock()
            context.__enter__ = Mock(return_value=session)
            context.__exit__ = Mock(return_value=False)
            stubs["requests"].Session.return_value = context
            with self.assertRaises(StopLoop):
                face.watch_render_server()
            self.assertEqual(state.read(state.quarantine / path.name), previous)
            self.assertEqual(state.pending(), [])
            session.get.assert_called_once()
            face.process_train.assert_called_once()
            self.assertEqual(state.completed_result("next-task")["status"], "success")

    def test_lease_heartbeat_uses_verified_session_and_stops_on_stale_lease(self):
        stubs = dependency_stubs()
        face = load_program("fin_face.py", stubs)
        session = SimpleNamespace(verify=True, post=Mock(return_value=SimpleNamespace(status_code=409)))
        context = Mock()
        context.__enter__ = Mock(return_value=session)
        context.__exit__ = Mock(return_value=False)
        stubs["requests"].Session.return_value = context
        heartbeat = face.LeaseHeartbeat({"task_id": "task-1", "lease_token": "test-lease"})
        heartbeat.stopped = SimpleNamespace(wait=Mock(return_value=False))
        heartbeat._run()
        self.assertTrue(heartbeat.lost.is_set())
        self.assertIs(session.verify, True)
        self.assertEqual(session.post.call_args.args[0], "https://idontlivealone.onrender.com/api/face/tasks/task-1/lease")
        self.assertEqual(session.post.call_args.kwargs["json"], {"device_id": "door-camera-a", "lease_token": "test-lease"})

    def test_lease_deadline_uses_claim_and_renew_request_start(self):
        stubs = dependency_stubs()
        face = load_program("fin_face.py", stubs)
        clock = Mock(return_value=90)
        heartbeat = face.LeaseHeartbeat({"task_id": "task-1", "lease_token": "test-lease"}, claimed_at=0, clock=clock)
        heartbeat.assert_owned()
        clock.return_value = 120
        with self.assertRaises(RuntimeError):
            heartbeat.assert_owned()
        clock.return_value = 100
        def renew(*args, **kwargs):
            clock.return_value = 140  # Response is delayed; do not extend from arrival time.
            return SimpleNamespace(status_code=200)
        session = SimpleNamespace(post=Mock(side_effect=renew))
        context = Mock()
        context.__enter__ = Mock(return_value=session)
        context.__exit__ = Mock(return_value=False)
        stubs["requests"].Session.return_value = context
        heartbeat.stopped = SimpleNamespace(wait=Mock(side_effect=[False, True]))
        heartbeat._run()
        clock.return_value = 219
        heartbeat.assert_owned()
        clock.return_value = 220
        with self.assertRaises(RuntimeError):
            heartbeat.assert_owned()

    def test_failed_lease_task_is_not_enrolled_by_later_training(self):
        stubs = dependency_stubs()
        cv2 = stubs["cv2"]
        cv2.face = SimpleNamespace(LBPHFaceRecognizer_create=FakeRecognizer)
        cv2.IMREAD_COLOR, cv2.IMREAD_GRAYSCALE, cv2.COLOR_BGR2GRAY = 1, 0, 2
        cv2.imdecode = lambda *_: b"image"
        cv2.cvtColor = lambda image, _: image
        cv2.imwrite = lambda path, _: Path(path).write_bytes(b"normalized") > 0
        cv2.imread = lambda path, _: Path(path).read_bytes()
        cv2.resize = lambda image, _: image
        cv2.equalizeHist = lambda image: image
        face = load_program("fin_face.py", stubs)
        face.detect_faces = lambda *_, **__: [(0, 0, 10, 10)]
        face.normalize_face = lambda *args: b"normalized"
        session = SimpleNamespace(get=Mock(return_value=SimpleNamespace(content=b"image", raise_for_status=lambda: None)))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            face.MODEL_PATH = root / "trainer" / "trainer.yml"
            face.MAP_FILE_PATH = root / "trainer" / "user_map.txt"
            face.FACEDATA_DIR = root / "facedata"
            face.MODEL_PATH.parent.mkdir()
            face.MODEL_PATH.write_text(json.dumps({"labels": [11]}))
            face.MAP_FILE_PATH.write_text("legacy=11\n")
            first_task = {"task_id": "task-a", "user_id": "failed-user", "image_urls": ["/pictures/task-a/file.jpg"]}
            failed_lease = SimpleNamespace(assert_owned=Mock(side_effect=[None, None, None, RuntimeError("lease lost")]))
            with self.assertRaises(RuntimeError):
                face.process_train(first_task, session, failed_lease)
            self.assertEqual(json.loads(face.MODEL_PATH.read_text())["labels"], [11])
            self.assertEqual(list(training_image_paths(face.FACEDATA_DIR)), [])
            second_task = {"task_id": "task-b", "user_id": "successful-user", "image_urls": ["/pictures/task-b/file.jpg"]}
            face.process_train(second_task, session, SimpleNamespace(assert_owned=Mock()))
            labels = json.loads(face.MODEL_PATH.read_text())["labels"]
            self.assertEqual(len(labels), 2)
            self.assertNotIn(face.get_or_create_int_id("failed-user"), labels)
            self.assertIn(face.get_or_create_int_id("successful-user"), labels)

    def test_completed_and_interrupted_training_do_not_duplicate_model_samples(self):
        stubs = dependency_stubs()
        cv2 = stubs["cv2"]
        cv2.face = SimpleNamespace(LBPHFaceRecognizer_create=FakeRecognizer)
        cv2.IMREAD_COLOR, cv2.IMREAD_GRAYSCALE, cv2.COLOR_BGR2GRAY = 1, 0, 2
        cv2.imdecode = lambda *_: b"image"
        cv2.cvtColor = lambda image, _: image
        cv2.imwrite = lambda path, _: Path(path).write_bytes(b"normalized") > 0
        cv2.imread = lambda path, _: Path(path).read_bytes()
        cv2.resize = lambda image, _: image
        cv2.equalizeHist = lambda image: image
        face = load_program("fin_face.py", stubs)
        face.detect_faces = lambda *_, **__: [(0, 0, 10, 10)]
        face.normalize_face = lambda *args: b"normalized"
        session = SimpleNamespace(get=Mock(return_value=SimpleNamespace(content=b"image", raise_for_status=lambda: None)))
        lease = SimpleNamespace(assert_owned=Mock())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            face.MODEL_PATH = root / "trainer" / "trainer.yml"
            face.MAP_FILE_PATH = root / "trainer" / "user_map.txt"
            face.FACEDATA_DIR = root / "facedata"
            face.MODEL_PATH.parent.mkdir()
            face.MODEL_PATH.write_text(json.dumps({"labels": [11]}))
            face.MAP_FILE_PATH.write_text("legacy=11\n")
            legacy = face.FACEDATA_DIR / "11" / "0.jpg"
            legacy.parent.mkdir(parents=True)
            legacy.write_bytes(b"legacy-face")
            task = {"task_id": "job-1", "user_id": "new-user", "image_urls": ["/pictures/job-1/file.jpg"]}
            state = TaskState(root / "state")

            def train_then_crash():
                face.process_train(task, session, lease)
                raise RuntimeError("Crash after model write, before receipt")

            with self.assertRaises(RuntimeError):
                state.run_once(task["task_id"], train_then_crash)
            self.assertEqual(len(json.loads(face.MODEL_PATH.read_text())["labels"]), 2)
            restarted = TaskState(root / "state")
            operation = Mock(side_effect=lambda: face.process_train(task, session, lease))
            restarted.run_once(task["task_id"], operation)
            self.assertEqual(len(json.loads(face.MODEL_PATH.read_text())["labels"]), 2)
            restarted.run_once(task["task_id"], operation)
            operation.assert_called_once()
            session.get.assert_called_once()
            self.assertEqual(legacy.read_bytes(), b"legacy-face")
            self.assertEqual(json.loads((face.MODEL_PATH.parent / "legacy_base.yml").read_text())["labels"], [11])
            task["task_id"] = "job-2"
            restarted.run_once(task["task_id"], lambda: face.process_train(task, session, lease))
            self.assertEqual(len(json.loads(face.MODEL_PATH.read_text())["labels"]), 3)

if __name__ == "__main__":
    unittest.main()
