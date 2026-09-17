import datetime as dt
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pi_runtime import CaptureQueue


TASK = {"captureTaskId": "capture-test-id", "eventId": "event-test-id", "userNo": 42,
        "eventDate": "2026-09-16", "expiresAt": "2026-09-16T03:02:00Z", "leaseToken": "lease-test"}
NOW = dt.datetime(2026, 9, 16, 3, 1, tzinfo=dt.timezone.utc).timestamp()


class CaptureQueueTests(unittest.TestCase):
    def test_lost_upload_ack_replays_identical_photo_after_restart(self):
        with tempfile.TemporaryDirectory() as root:
            frames = Mock(return_value=b"original-jpeg")
            client = SimpleNamespace(claim=Mock(return_value=(200, TASK)), upload=Mock(side_effect=ConnectionError("lost")), failed=Mock())
            first = CaptureQueue(root, 42, client, frames, clock=lambda: NOW)
            with self.assertRaises(ConnectionError):
                first.step()
            client.claim.return_value = (204, None)
            client.upload.side_effect = None
            client.upload.return_value = (200, {"imageUrl": "https://example.test/picture.jpg"})
            later = Mock(return_value=b"later-jpeg")
            CaptureQueue(root, 42, client, later, clock=lambda: NOW + 3600).step()
            self.assertEqual([call.args[1] for call in client.upload.call_args_list], [b"original-jpeg", b"original-jpeg"])
            later.assert_not_called()
            self.assertFalse(list(first.pending.glob("*.json")))

    def test_expired_task_does_not_obtain_camera_frame(self):
        with tempfile.TemporaryDirectory() as root:
            frames = Mock(return_value=b"late-jpeg")
            client = SimpleNamespace(claim=Mock(return_value=(200, TASK)), upload=Mock(), failed=Mock())
            self.assertEqual(CaptureQueue(root, 42, client, frames, clock=lambda: NOW + 120).step(), "expired")
            frames.assert_not_called()
            client.upload.assert_not_called()
            client.failed.assert_called_once()

    def test_old_lease_quarantines_frame_and_allows_next_task(self):
        with tempfile.TemporaryDirectory() as root:
            client = SimpleNamespace(claim=Mock(side_effect=[(200, TASK), (204, None)]), upload=Mock(return_value=(409, None)), failed=Mock())
            queue = CaptureQueue(root, 42, client, Mock(return_value=b"original"), clock=lambda: NOW, logger=Mock())
            self.assertEqual(queue.step(), "quarantined")
            self.assertEqual(queue.step(), "idle")
            self.assertEqual(len(list(queue.quarantine.glob("*.jpg"))), 1)
            self.assertFalse(list(queue.pending.glob("*.json")))

    def test_other_owner_never_captures_or_uploads(self):
        with tempfile.TemporaryDirectory() as root:
            client = SimpleNamespace(claim=Mock(return_value=(200, {**TASK, "userNo": 99})), upload=Mock(), failed=Mock())
            frame = Mock()
            with self.assertRaises(ValueError):
                CaptureQueue(root, 42, client, frame, clock=lambda: NOW).step()
            frame.assert_not_called()
            client.upload.assert_not_called()


if __name__ == "__main__":
    unittest.main()
