import datetime as dt
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from event_runtime import Config, DeliveryWorker, EventGate, EventOutbox, event_key, make_event
import DB_security as program

def config(root):
    return Config({"USER_NO": "42", "DEVICE_API_TOKEN": "test-only-token-" * 3, "PI_B_DATA_DIR": str(root), "CAMERA_TRIGGER_URL": "http://door-camera-a.local:5002/trigger_event", "EVENT_PHOTOS_ENABLED": "true"}, ROOT)

def payload(key="SECURITY_HIGH"):
    return make_event(key, 42, dt.datetime(2026, 9, 16, 12, 0, tzinfo=dt.timezone(dt.timedelta(hours=9))))

def ack(event, duplicate=False):
    return 200, {"success": True, "eventId": event["eventId"], "logId": 7, "userNo": event["userNo"], "eventDate": "2026-09-16", "duplicate": duplicate}

def photo(event):
    return 200, {"status": "success", "log_id": event["eventId"], "user_no": 42, "image_url": "https://example.test/uploads/photo.jpg"}

class EventsTests(unittest.TestCase):
    def test_no_photo_policy_keeps_sensor_logs_in_paired_and_legacy_modes(self):
        for paired in (True, False):
            with self.subTest(paired=paired), tempfile.TemporaryDirectory() as root:
                settings = config(root)
                settings.pairing_enabled = paired
                settings.event_photos_enabled = False
                event, box = payload(), EventOutbox(root)
                path = box.enqueue(event, 120)
                code, body = ack(event)
                body.update(cameraCaptureQueued=False, captureTaskId=None, captureStatus="NOT_REQUESTED")
                client = SimpleNamespace(register=Mock(return_value=(code, body)), capture=Mock())
                self.assertEqual(DeliveryWorker(settings, box, client, logger=Mock()).process(path), "complete")
                client.register.assert_called_once()
                client.capture.assert_not_called()

    def test_paired_mode_needs_no_ip_user_number_or_shared_server_secret(self):
        with tempfile.TemporaryDirectory() as root:
            settings = Config({"PI_B_DATA_DIR": root}, ROOT)
            self.assertTrue(settings.pairing_enabled)
            self.assertIsNone(settings.user_no)
            self.assertIsNone(settings.camera_url)
            settings.user_no = 42  # Supplied only by authenticated device status.
            event, box = payload(), EventOutbox(root)
            path = box.enqueue(event, 120)
            code, body = ack(event)
            body.update(cameraCaptureQueued=True, captureTaskId="capture-1", captureStatus="QUEUED")
            client = SimpleNamespace(register=Mock(return_value=(code, body)), capture=Mock())
            worker = DeliveryWorker(settings, box, client, clock=lambda: 0, logger=Mock())
            self.assertEqual(worker.process(path), "complete")
            client.capture.assert_not_called()
            saved = box.read(box.completed / path.name)
            self.assertEqual(saved["photoStatus"], "server_managed")
            self.assertEqual(saved["captureStatus"], "QUEUED")
            self.assertNotIn("imageUrl", saved)

    def test_paired_mode_rejects_ack_without_capture_reservation_status(self):
        with tempfile.TemporaryDirectory() as root:
            settings = Config({"PI_B_DATA_DIR": root}, ROOT)
            settings.user_no = 42
            event, box = payload(), EventOutbox(root)
            path = box.enqueue(event, 120)
            client = SimpleNamespace(register=Mock(return_value=ack(event)), capture=Mock())
            worker = DeliveryWorker(settings, box, client, logger=Mock())
            self.assertEqual(worker.process(path), "retry")
            self.assertEqual(box.read(path)["phase"], "event")
            client.capture.assert_not_called()

    def test_changed_pairing_cannot_reassign_saved_event(self):
        with tempfile.TemporaryDirectory() as root:
            settings, event, box = config(root), payload(), EventOutbox(root)
            settings.user_no = 99
            path = box.enqueue(event, 120)
            client = SimpleNamespace(register=Mock(), capture=Mock())
            self.assertEqual(DeliveryWorker(settings, box, client, logger=Mock()).process(path), "retry")
            client.register.assert_not_called()
            self.assertEqual(box.read(path)["event"]["userNo"], 42)

    def test_serial_mapping_priority_and_cooldown_are_preserved(self):
        self.assertIsNone(event_key("unrecognized message"))
        self.assertEqual(event_key("UART:SENSOR_MOVE"), "SENSOR_MOVE")
        self.assertEqual(event_key("SECURITY_HIGH SENSOR_MOVE"), "SENSOR_MOVE")
        self.assertEqual(payload("SECURITY_MIDDLE")["severity"], "high")
        self.assertEqual(payload("SECURITY_LOW")["val1"], 0.5)
        gate = EventGate()
        self.assertTrue(gate.accepts("SECURITY_LOW", 1))
        gate.mark("SECURITY_LOW", 1)
        self.assertFalse(gate.accepts("SECURITY_LOW", 2))
        self.assertTrue(gate.accepts("SECURITY_HIGH", 2))
        gate.mark("SECURITY_HIGH", 2)
        self.assertFalse(gate.accepts("SENSOR_MOVE", 3))
        self.assertTrue(gate.accepts("SENSOR_MOVE", 5))

    def test_configuration_requires_https_account_and_token(self):
        base = {"USER_NO": "42", "DEVICE_API_TOKEN": "test-only-token-" * 3}
        self.assertEqual(Config(base, ROOT).uart_port, "/dev/serial0")
        self.assertEqual(Config(base, ROOT).baud_rate, 115200)
        for override in ({"USER_NO": "0"}, {"DEVICE_API_TOKEN": ""}, {"DEVICE_API_TOKEN": "too-short"}, {"RENDER_SERVER_URL": "http://example.test"}, {"CAMERA_TRIGGER_URL": "http://user:secret@example.test"}):
            with self.assertRaises(ValueError):
                Config({**base, **override}, ROOT)
        with self.assertRaises(ValueError):
            make_event("SENSOR_MOVE", 42, dt.datetime(2026, 9, 16))

    def test_event_ack_precedes_photo_and_restart_keeps_phase_and_id(self):
        with tempfile.TemporaryDirectory() as root:
            settings, event = config(root), payload()
            box = EventOutbox(root)
            path = box.enqueue(event, 120)
            now = dt.datetime.fromisoformat(event["occurredAt"]).timestamp() + 1
            client = SimpleNamespace(register=Mock(return_value=ack(event)), capture=Mock(side_effect=ConnectionError("offline")))
            worker = DeliveryWorker(settings, box, client, clock=lambda: now, logger=Mock())
            with self.assertRaises(ConnectionError):
                worker.process(path)
            self.assertEqual(box.read(path)["phase"], "photo")
            restarted = EventOutbox(root)
            client.capture.side_effect = None
            client.capture.return_value = photo(event)
            self.assertEqual(DeliveryWorker(settings, restarted, client, clock=lambda: now).process(path), "complete")
            client.register.assert_called_once_with(event)
            client.capture.assert_called_with(event["eventId"], 42)
            self.assertEqual(restarted.paths(), [])
            self.assertNotIn(settings.token, (box.completed / path.name).read_text(encoding="utf-8"))

    def test_unacknowledged_event_never_requests_photo_and_replays_same_payload(self):
        with tempfile.TemporaryDirectory() as root:
            event, box = payload(), EventOutbox(root)
            path = box.enqueue(event, 120)
            client = SimpleNamespace(register=Mock(side_effect=[(503, None), ack(event, duplicate=True)]), capture=Mock(return_value=photo(event)))
            worker = DeliveryWorker(config(root), box, client, clock=lambda: dt.datetime.fromisoformat(event["occurredAt"]).timestamp() + 1, logger=Mock())
            self.assertEqual(worker.process(path), "retry")
            client.capture.assert_not_called()
            self.assertEqual(worker.process(path), "complete")
            self.assertEqual(client.register.call_args_list[0], client.register.call_args_list[1])

    def test_incorrect_or_malformed_ack_is_not_success(self):
        for response in ((200, {"success": True}), (200, {"success": True, "eventId": "different", "userNo": 42, "logId": 7})):
            with self.subTest(response=response), tempfile.TemporaryDirectory() as root:
                event, box = payload(), EventOutbox(root)
                path = box.enqueue(event, 120)
                client = SimpleNamespace(register=Mock(return_value=response), capture=Mock())
                worker = DeliveryWorker(config(root), box, client, logger=Mock())
                self.assertEqual(worker.process(path), "retry")
                client.capture.assert_not_called()

    def test_expired_event_is_registered_but_never_captures_late_scene(self):
        with tempfile.TemporaryDirectory() as root:
            event, box = payload(), EventOutbox(root)
            path = box.enqueue(event, 120)
            client = SimpleNamespace(register=Mock(return_value=ack(event)), capture=Mock())
            worker = DeliveryWorker(config(root), box, client, clock=lambda: dt.datetime.fromisoformat(event["occurredAt"]).timestamp() + 121, logger=Mock())
            self.assertEqual(worker.process(path), "expired")
            client.register.assert_called_once()
            client.capture.assert_not_called()
            self.assertEqual(box.read(box.completed / path.name)["photoStatus"], "expired")

    def test_event_conflict_is_quarantined_but_auth_failure_is_retained(self):
        for status in (409, 401, 403):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as root:
                event, box = payload(), EventOutbox(root)
                path = box.enqueue(event, 120)
                client = SimpleNamespace(register=Mock(return_value=(status, None)), capture=Mock())
                outcome = DeliveryWorker(config(root), box, client, logger=Mock()).process(path)
                self.assertEqual(outcome, "quarantined" if status == 409 else "retry")
                self.assertTrue((box.quarantine / path.name).exists() if status == 409 else path.exists())
                client.capture.assert_not_called()

    def test_camera_owner_mismatch_is_persisted_and_not_photo_success(self):
        with tempfile.TemporaryDirectory() as root:
            event, box = payload(), EventOutbox(root)
            path = box.enqueue(event, 120)
            client = SimpleNamespace(register=Mock(return_value=ack(event)), capture=Mock(return_value=(403, None)))
            worker = DeliveryWorker(config(root), box, client, clock=lambda: dt.datetime.fromisoformat(event["occurredAt"]).timestamp() + 1, logger=Mock())
            self.assertEqual(worker.process(path), "retry")
            saved = box.read(path)
            self.assertEqual(saved["phase"], "photo")
            self.assertIn("USER_NO", saved["lastError"])
            self.assertNotIn("imageUrl", saved)

    def test_server_bearer_is_never_sent_to_camera(self):
        settings = config(ROOT)
        with patch.object(program.urllib.request, "build_opener", return_value=Mock()), patch.object(program.ssl, "create_default_context", return_value=Mock()):
            client = program.ApiClient(settings)
        client.send = Mock(return_value=(200, {}))
        event = payload()
        client.register(event)
        server_request = client.send.call_args.args[0]
        self.assertEqual(server_request.get_header("Authorization"), "Bearer " + settings.token)
        self.assertEqual(server_request.method, "POST")
        self.assertEqual(server_request.full_url, "https://idontlivealone.onrender.com/api/device/events")
        client.capture(event["eventId"], 42)
        camera_request = client.send.call_args.args[0]
        self.assertIsNone(camera_request.get_header("Authorization"))
        self.assertIn("expected_user_no=42", camera_request.full_url)
        self.assertIn(event["eventId"], camera_request.full_url)

if __name__ == "__main__":
    unittest.main()
