"""Deterministic pipe/watchdog tests; no camera, child processes, or sockets."""
from contextlib import ExitStack
import subprocess
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from test_runtime import dependency_stubs, load_program


def child(returncode=None):
    return SimpleNamespace(stdout=SimpleNamespace(fileno=lambda: 42, close=Mock()),
                           poll=Mock(return_value=returncode), terminate=Mock(),
                           kill=Mock(), wait=Mock(return_value=0))


class Pipe:
    def __init__(self, events=(), after_read=None):
        self.events = list(events)
        self.now = 0.0
        self.after_read = after_read
        self.reads = 0

    def clock(self):
        return self.now

    def select(self, readers, writers, errors, timeout):
        if self.events and self.events[0][0] <= self.now + timeout:
            self.now = max(self.now, self.events[0][0])
            return readers, [], []
        self.now += timeout
        return [], [], []

    def read(self, descriptor, size):
        _, value = self.events.pop(0)
        self.reads += 1
        if isinstance(value, Exception):
            raise value
        if len(value) > size:
            raise AssertionError("Mock chunk exceeds the real os.read limit")
        if self.after_read:
            self.after_read(self)
        return value


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.camera = load_program("fin_camera.py", dependency_stubs())
        self.stopped = threading.Event()
        self.process = child()

    def pipe(self, driver):
        stack = ExitStack()
        stack.enter_context(patch.object(self.camera.select, "select", side_effect=driver.select))
        stack.enter_context(patch.object(self.camera.os, "read", side_effect=driver.read))
        nonblocking = stack.enter_context(patch.object(self.camera.os, "set_blocking"))
        self.addCleanup(stack.close)
        return nonblocking

    def test_silent_live_children_time_out_restart_and_are_reaped(self):
        driver = Pipe()
        nonblocking = self.pipe(driver)
        processes = [child(), child()]
        waits = []
        def wait(seconds):
            waits.append(seconds)
            driver.now += seconds
            if len(waits) == 2:
                self.stopped.set()
        stopped = SimpleNamespace(is_set=self.stopped.is_set, wait=wait)
        read = self.camera.read_camera_frames
        self.camera.frames.put(b"old frame")
        with patch.object(self.camera.subprocess, "Popen", side_effect=processes) as popen, \
                patch.object(self.camera, "read_camera_frames", side_effect=lambda p, e: read(p, e, driver.clock)):
            self.camera.capture_camera(stopped)
        self.assertEqual(popen.call_count, 2)
        self.assertEqual(driver.reads, 0)
        self.assertEqual(driver.now, 24)
        self.assertEqual(nonblocking.call_count, 2)
        self.assertIsNone(self.camera.frames.get()[0])
        for process in processes:
            process.terminate.assert_called_once()
            process.wait.assert_called_once_with(timeout=2)
            process.kill.assert_not_called()
            process.stdout.close.assert_called_once()

    def test_split_markers_and_multiple_jpegs_publish_latest_without_extra_delay(self):
        first, latest = b"\xff\xd8first\xff\xd9", b"\xff\xd8latest\xff\xd9"
        driver = Pipe([(0, b"noise\xff"), (0.02, b"\xd8first\xff"),
                       (0.04, b"\xd9" + latest)],
                      after_read=lambda p: self.stopped.set() if not p.events else None)
        nonblocking = self.pipe(driver)
        put = Mock(wraps=self.camera.frames.put)
        with patch.object(self.camera.frames, "put", put):
            self.camera.read_camera_frames(self.process, self.stopped, driver.clock)
        self.assertEqual([call.args[0] for call in put.call_args_list], [first, latest])
        self.assertEqual(self.camera.frames.get()[0], latest)
        self.assertEqual(driver.now, 0.04)
        nonblocking.assert_called_once_with(42, False)

    def test_complete_frames_extend_watchdog_but_partial_bytes_do_not(self):
        jpeg = b"\xff\xd8valid\xff\xd9"
        driver = Pipe([(9, jpeg), (18, jpeg + b"\xff\xd8partial")])
        self.pipe(driver)
        with self.assertRaisesRegex(TimeoutError, "complete JPEG"):
            self.camera.read_camera_frames(self.process, self.stopped, driver.clock)
        self.assertEqual(driver.now, 28)
        self.assertEqual(self.camera.frames.sequence, 2)

    def test_continuous_garbage_and_incomplete_jpegs_cannot_reset_watchdog(self):
        for data in (b"garbage", b"\xff\xd8partial", b"\xff\xd8\xff\xd9"):
            with self.subTest(data=data):
                driver = Pipe([(second, data) for second in range(1, 13)])
                with patch.object(self.camera.select, "select", side_effect=driver.select), \
                        patch.object(self.camera.os, "read", side_effect=driver.read), \
                        patch.object(self.camera.os, "set_blocking"):
                    with self.assertRaises(TimeoutError):
                        self.camera.read_camera_frames(self.process, self.stopped, driver.clock)
                self.assertEqual(driver.now, 10)
                self.assertIsNone(self.camera.frames.get()[0])

    def test_malformed_or_oversized_frame_recovers_at_next_jpeg(self):
        jpeg = b"\xff\xd8latest\xff\xd9"
        driver = Pipe([(0, b"\xff\xd8" + b"x" * 40),
                       (0.01, b"noise\xff\xd8unfinished" + jpeg)],
                      after_read=lambda p: self.stopped.set() if not p.events else None)
        self.pipe(driver)
        with patch.object(self.camera, "MAX_CAPTURE_JPEG_BYTES", 32):
            self.camera.read_camera_frames(self.process, self.stopped, driver.clock)
        self.assertEqual(self.camera.frames.get()[0], jpeg)
        self.assertEqual(self.camera.frames.sequence, 1)

    def test_nonblocking_read_races_still_expire_and_eof_is_immediate(self):
        driver = Pipe([(1, BlockingIOError()), (2, InterruptedError()), (2.1, b"")])
        self.pipe(driver)
        with self.assertRaisesRegex(RuntimeError, "ended its output"):
            self.camera.read_camera_frames(self.process, self.stopped, driver.clock)
        self.assertEqual(driver.now, 2.1)
        self.assertIsNone(self.camera.frames.get()[0])

    def test_stopped_child_without_readable_output_is_detected(self):
        driver = Pipe()
        self.pipe(driver)
        with self.assertRaisesRegex(RuntimeError, "stopped"):
            self.camera.read_camera_frames(child(returncode=1), self.stopped, driver.clock)
        self.assertEqual(driver.now, 0.5)

    def test_stop_request_reaps_child_without_spawning_replacement(self):
        def select(*_):
            self.stopped.set()
            return [], [], []
        with patch.object(self.camera.select, "select", side_effect=select), \
                patch.object(self.camera.os, "set_blocking"), \
                patch.object(self.camera.os, "read") as read, \
                patch.object(self.camera.subprocess, "Popen", return_value=self.process) as popen:
            self.camera.capture_camera(self.stopped)
        read.assert_not_called()
        popen.assert_called_once()
        self.process.terminate.assert_called_once()
        self.process.wait.assert_called_once_with(timeout=2)
        self.process.stdout.close.assert_called_once()

    def test_child_ignoring_termination_is_killed_and_reaped(self):
        self.process.wait.side_effect = [subprocess.TimeoutExpired("rpicam-vid", 2), 0]
        self.camera.reap_camera(self.process)
        self.process.terminate.assert_called_once()
        self.process.kill.assert_called_once()
        self.assertEqual(self.process.wait.call_count, 2)
        self.process.stdout.close.assert_called_once()

    def test_unreapable_child_never_starts_competing_camera_process(self):
        self.process.wait.side_effect = subprocess.TimeoutExpired("rpicam-vid", 2)
        with patch.object(self.camera, "read_camera_frames", side_effect=TimeoutError("stalled")), \
                patch.object(self.camera.subprocess, "Popen", return_value=self.process) as popen:
            self.camera.capture_camera(self.stopped)
        popen.assert_called_once()
        self.process.kill.assert_called_once()
        self.assertEqual(self.process.wait.call_count, 2)
        self.process.stdout.close.assert_called_once()
        self.assertIsNone(self.camera.frames.get()[0])


if __name__ == "__main__":
    unittest.main()
