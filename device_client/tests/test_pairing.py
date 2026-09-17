import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from device_pairing import DeviceClient, JsonTransport


def status(paired=False):
    body = {"deviceId": "camera-test-id", "role": "CAMERA", "name": "Front door", "paired": paired}
    if paired:
        body["userNo"] = 42
    else:
        body.update(pairingCode="01234567", expiresAt="2026-09-16T03:10:00Z")
    return body


class PairingTests(unittest.TestCase):
    def test_owner_cannot_change_after_unlink_and_restart(self):
        with tempfile.TemporaryDirectory() as root:
            first = DeviceClient(root, "CAMERA", transport=Mock())
            first.accept_status(status(True))
            first.accept_status(status(False))
            restored = DeviceClient(root, "CAMERA", transport=Mock())
            with self.assertRaises(ValueError):
                restored.accept_status({**status(True), "userNo": 99})
            self.assertIsNone(restored.user_no)
            restored.accept_status(status(True))
            self.assertEqual(restored.user_no, 42)

    def test_credential_persisted_before_request_and_reused_after_lost_response(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "credentials.json"
            transport = Mock(side_effect=ConnectionError("offline"))
            first = DeviceClient(root, "CAMERA", transport=transport)
            self.assertTrue(path.is_file())
            with self.assertRaises(ConnectionError):
                first.enroll()
            restored = DeviceClient(root, "CAMERA", transport=Mock(return_value=(200, status())))
            restored.enroll()
            self.assertEqual(first.token, restored.token)
            self.assertNotIn(first.token, (Path(root) / "pairing-code.json").read_text())
            if os.name != "nt":
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_pairing_wait_uses_server_account_and_never_env_guess(self):
        with tempfile.TemporaryDirectory() as root:
            transport = Mock(side_effect=[(200, status()), (200, status()), (200, status(True))])
            client = DeviceClient(root, "CAMERA", transport=transport, logger=Mock())
            with patch("device_pairing.time.sleep"):
                result = client.wait_paired(interval=0)
            self.assertEqual(result["userNo"], 42)
            self.assertEqual(client.user_no, 42)
            self.assertFalse((Path(root) / "pairing-code.json").exists())
            self.assertEqual({call.args[2] for call in transport.call_args_list}, {client.token})

    def test_bad_role_account_or_owner_change_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            client = DeviceClient(root, "CAMERA", transport=Mock())
            for override in ({"role": "SENSOR"}, {"userNo": True}, {"userNo": 0}):
                with self.assertRaises(ValueError):
                    client.accept_status({**status(True), **override})
            client.accept_status(status(True))
            with self.assertRaises(ValueError):
                client.accept_status({**status(True), "userNo": 99})
            self.assertIsNone(client.user_no)
            with self.assertRaises(ValueError):
                DeviceClient(root, "SENSOR", transport=Mock())

    def test_tls_verification_and_https_credential_origin_are_mandatory(self):
        with patch("device_pairing.ssl.create_default_context") as context:
            JsonTransport()
            context.assert_called_once()
        with tempfile.TemporaryDirectory() as root:
            for server in ("http://example.test", "https://name:secret@example.test", "https://example.test?token=value"):
                with self.assertRaises(ValueError):
                    DeviceClient(root, "CAMERA", server_url=server, transport=Mock())

    def test_unauthorized_status_clears_in_memory_owner(self):
        with tempfile.TemporaryDirectory() as root:
            client = DeviceClient(root, "CAMERA", transport=Mock(return_value=(401, None)))
            client.accept_status(status(True))
            with self.assertRaises(RuntimeError):
                client.refresh()
            self.assertIsNone(client.user_no)


if __name__ == "__main__":
    unittest.main()
