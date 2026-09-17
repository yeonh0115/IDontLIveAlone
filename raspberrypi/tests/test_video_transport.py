"""Camera transport tests: no network, camera, GPIO or third-party packages."""
import contextlib
import io
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from test_runtime import ROOT, StopLoop, dependency_stubs, load_program
from pi_runtime import (
    AckVideoSender, CameraAckInvalid, CameraAckUnsupported, LatestFrame,
    MAX_STREAM_JPEG_BYTES, Settings, face_detection_size, require_camera_ack,
    send_frame_with_ack,
)


class Clock:
    def __init__(self):
        self.value = 0.0

    def __call__(self):
        return self.value


class VideoTests(unittest.TestCase):
    def test_validated_settings_and_expected_defaults(self):
        settings = Settings({}, ROOT)
        self.assertEqual((settings.camera_width, settings.camera_height, settings.camera_jpeg_quality), (640, 480, 70))
        self.assertEqual((settings.camera_capture_fps, settings.camera_cloud_fps, settings.camera_ack_timeout), (15, 10, 2))
        self.assertEqual(settings.camera_max_frame_age, 0.5)
        self.assertFalse(settings.event_photos_enabled)
        high = Settings({"CAMERA_WIDTH": "1280", "CAMERA_HEIGHT": "720", "CAMERA_CAPTURE_FPS": "8", "CAMERA_CLOUD_FPS": "5"}, ROOT)
        self.assertEqual((high.camera_width, high.camera_height, high.camera_cloud_fps), (1280, 720, 5))
        for values in (
            {"CAMERA_WIDTH": "641"}, {"CAMERA_HEIGHT": "721"}, {"CAMERA_WIDTH": "1920"},
            {"CAMERA_JPEG_QUALITY": "0"}, {"CAMERA_JPEG_QUALITY": "101"},
            {"CAMERA_CLOUD_FPS": "16"}, {"CAMERA_CAPTURE_FPS": "31"},
            {"CAMERA_CLOUD_FPS": "nan"}, {"CAMERA_ACK_TIMEOUT": "inf"},
            {"CAMERA_ACK_TIMEOUT": "0"}, {"CAMERA_MAX_FRAME_AGE": "3"},
            {"CAMERA_WIDTH": "640.5"},
        ):
            with self.subTest(values=values), self.assertRaises(ValueError):
                Settings(values, ROOT)

    def test_ack_negotiation_is_required_and_header_case_insensitive(self):
        require_camera_ack(SimpleNamespace(getheaders=lambda: {"X-CAMERA-ACK": "1"}))
        for headers in (None, {}, {"X-Camera-Ack": "0"}, {"X-Camera-Ack": "1,1"}):
            with self.assertRaises(CameraAckUnsupported):
                require_camera_ack(SimpleNamespace(getheaders=lambda: headers))

    def test_one_deadline_covers_send_and_ack_and_cleans_watchdog(self):
        clock = Clock()
        connection = Mock()
        def sent(_):
            clock.value += 1.2
        def acknowledged():
            clock.value += 0.7
            return "ack"
        connection.send_binary.side_effect = sent
        connection.recv.side_effect = acknowledged
        timer = Mock()
        elapsed = send_frame_with_ack(connection, b"jpeg", 2, clock, lambda *_: timer)
        self.assertAlmostEqual(elapsed, 1.9)
        timeouts = [call.args[0] for call in connection.settimeout.call_args_list]
        self.assertAlmostEqual(timeouts[0], 2)
        self.assertAlmostEqual(timeouts[1], 0.8)
        timer.cancel.assert_called_once()
        timer.join.assert_called_once()
        connection.abort.assert_not_called()

    def test_slow_send_cannot_get_a_new_full_receive_deadline(self):
        clock = Clock()
        connection = Mock()
        connection.send_binary.side_effect = lambda _: setattr(clock, "value", 2.01)
        timer = Mock()
        with self.assertRaises(TimeoutError):
            send_frame_with_ack(connection, b"jpeg", 2, clock, lambda *_: timer)
        connection.recv.assert_not_called()
        connection.abort.assert_called_once()
        timer.cancel.assert_called_once()
        timer.join.assert_called_once()

    def test_watchdog_interrupts_a_blocked_partial_operation_and_is_joined(self):
        # A real short-lived Timer against an in-memory fake; no socket is opened.
        stopped = threading.Event()
        timers = []
        connection = Mock()
        connection.abort.side_effect = stopped.set
        def blocked(_):
            if not stopped.wait(1):
                raise AssertionError("watchdog did not abort blocked send")
            raise ConnectionError("aborted")
        connection.send_binary.side_effect = blocked
        def factory(delay, callback):
            timer = threading.Timer(delay, callback)
            timers.append(timer)
            return timer
        with self.assertRaises(ConnectionError):
            send_frame_with_ack(connection, b"jpeg", 0.02, timer_factory=factory)
        self.assertTrue(stopped.is_set())
        self.assertFalse(timers[0].is_alive())
        connection.recv.assert_not_called()

    def test_invalid_ack_and_oversized_jpeg_never_continue_the_connection(self):
        for wrong_ack in (b"ack", "unexpected", ""):
            connection = Mock()
            connection.recv.return_value = wrong_ack
            timer = Mock()
            with self.assertRaises(CameraAckInvalid):
                send_frame_with_ack(connection, b"jpeg", 2, timer_factory=lambda *_: timer)
            connection.abort.assert_called_once()
        connection = Mock()
        with self.assertRaises(ValueError):
            send_frame_with_ack(connection, b"x" * (MAX_STREAM_JPEG_BYTES + 1), 2)
        connection.send_binary.assert_not_called()

    def test_waiting_for_ack_selects_only_latest_frame_next(self):
        clock = Clock()
        frames = LatestFrame(clock=clock)
        frames.put(b"first")
        sent = []
        def send(_, frame, timeout):
            sent.append(frame)
            if len(sent) == 1:
                frames.put(b"discarded")
                frames.put(b"latest")
            clock.value += 0.25
            return 0.25
        sender = AckVideoSender(frames, Settings({}, ROOT), clock=clock, send=send)
        self.assertTrue(sender.step(None))
        self.assertTrue(sender.step(None))
        self.assertFalse(sender.step(None))
        self.assertEqual(sent, [b"first", b"latest"])

    def test_rate_limit_and_cloud_stale_limit_do_not_change_local_frame_age(self):
        clock = Clock()
        frames = LatestFrame(clock=clock)
        send = Mock(return_value=0.01)
        sender = AckVideoSender(frames, Settings({}, ROOT), clock=clock, send=send)
        frames.put(b"first")
        sender.step(None)
        frames.put(b"second")
        clock.value = 0.05
        self.assertFalse(sender.step(None))
        clock.value = 0.1
        self.assertTrue(sender.step(None))
        frames.put(b"now-stale")
        clock.value = 1
        self.assertFalse(sender.step(None))
        self.assertEqual(frames.get()[0], b"now-stale")
        self.assertEqual(send.call_count, 2)
        frames.put(b"x" * (MAX_STREAM_JPEG_BYTES + 1))
        self.assertFalse(sender.step(None))
        self.assertEqual(sender.oversized, 1)

    def test_aggregate_log_contains_only_counts_and_timings(self):
        clock = Clock()
        frames = LatestFrame(clock=clock)
        frames.put(b"private-image-content")
        logger = Mock()
        sender = AckVideoSender(frames, Settings({}, ROOT), clock=clock, logger=logger, send=Mock(return_value=0.125))
        sender.step(None)
        clock.value = 30
        sender.step(None)
        message = logger.call_args.args[0]
        self.assertIn("ack_mean_ms=125", message)
        self.assertIn("bytes=21", message)
        self.assertNotIn("private-image", message)
        self.assertEqual(sender.count, 0)

    def test_face_bounds_match_original_and_training_input_is_unchanged(self):
        for source, expected in (((320, 240), (160, 120)), ((640, 480), (160, 120)), ((1280, 720), (160, 90)), ((120, 90), (120, 90))):
            self.assertEqual(face_detection_size(*source), expected)
        stubs = dependency_stubs()
        face = load_program("fin_face.py", stubs)
        original = SimpleNamespace(shape=(480, 640, 3))
        resized = SimpleNamespace(shape=(120, 160, 3))
        face.cv2.resize = Mock(return_value=resized)
        face.cv2.COLOR_BGR2GRAY = 1
        face.cv2.cvtColor = Mock(side_effect=lambda frame, _: frame)
        face.detect_faces = Mock(return_value=[])
        face.predict_frame(original, reduce_size=True)
        face.cv2.resize.assert_called_once_with(original, (160, 120))
        face.detect_faces.assert_called_once_with(resized)
        face.cv2.resize.reset_mock()
        face.predict_frame(original)
        face.cv2.resize.assert_not_called()

    def test_camera_requests_ack_tls_nodelay_and_rejects_unsupported_server(self):
        stubs = dependency_stubs()
        camera = load_program("fin_camera.py", stubs)
        connection = Mock()
        connection.getheaders.return_value = {}
        stubs["websocket"].create_connection.return_value = connection
        output = io.StringIO()
        with patch.object(camera.time, "sleep", side_effect=StopLoop()), contextlib.redirect_stdout(output):
            with self.assertRaises(StopLoop):
                camera.upload_to_cloud_websocket()
        options = stubs["websocket"].create_connection.call_args.kwargs
        self.assertEqual(options["header"]["X-Camera-Ack"], "1")
        self.assertTrue(options["sslopt"]["check_hostname"])
        self.assertTrue(options["sockopt"])
        connection.send_binary.assert_not_called()
        connection.shutdown.assert_called_once()
        self.assertIn("CameraAckUnsupported", output.getvalue())
        self.assertNotIn("connected (ACK v1)", output.getvalue())


if __name__ == "__main__":
    unittest.main()
