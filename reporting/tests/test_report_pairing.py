import contextlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from report1 import main
from report_pairing import require_same_owner, resolve_config
from report_runtime import ApiError, ConfigurationError, ReportApi


class PairingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.env = {"DEVICE_STATE_DIR": self.temp.name, "REPORT_OUTBOX_DIR": self.temp.name}
        self.client = Mock()
        self.client.token = "mock-private-device-credential-00000000"
        self.identity = {"deviceId": "report-7", "role": "REPORT", "paired": True, "userNo": 37}
        self.client.wait_paired.return_value = self.identity
        self.client.refresh.return_value = self.identity
        self.factory = Mock(return_value=self.client)

    def test_automatic_pairing_uses_only_server_supplied_owner(self):
        config, device = resolve_config(self.env, self.factory)
        self.assertIs(device, self.client)
        self.assertEqual(37, config.user_no)
        self.assertEqual(self.client.token, config.api_token)
        self.assertEqual("REPORT", self.factory.call_args.kwargs["role"])
        self.assertNotIn(self.client.token, repr(config))

    def test_partial_legacy_settings_never_create_another_device(self):
        for values in ({"REPORT_USER_NO": "37"}, {"REPORT_API_TOKEN": "x" * 40}):
            with self.subTest(values=list(values)), self.assertRaises(ConfigurationError):
                resolve_config({**self.env, **values}, self.factory)
        self.factory.assert_not_called()

    def test_complete_legacy_settings_do_not_enroll(self):
        config, device = resolve_config({**self.env, "REPORT_USER_NO": "48", "REPORT_API_TOKEN": "x" * 40}, self.factory)
        self.assertEqual(48, config.user_no)
        self.assertIsNone(device)
        self.factory.assert_not_called()

    def test_unpaired_wrong_role_and_invalid_owner_are_rejected(self):
        for changed in ({"paired": False}, {"role": "SENSOR"}, {"userNo": True}, {"userNo": 0}):
            self.client.wait_paired.return_value = {**self.identity, **changed}
            with self.subTest(changed=changed), self.assertRaises(ConfigurationError):
                resolve_config(self.env, self.factory)

    def test_offline_check_does_not_create_credentials_or_call_network(self):
        output = io.StringIO()
        with patch.dict("os.environ", self.env, clear=True), patch("report1.prepare_device") as prepare, \
                patch("report1.resolve_config") as resolve, contextlib.redirect_stdout(output):
            self.assertEqual(0, main(["--check-config"]))
        prepare.assert_not_called()
        resolve.assert_not_called()
        self.assertEqual([], list(Path(self.temp.name).iterdir()))

    def test_pair_only_prints_code_without_reporter_or_paid_generation(self):
        self.client.enroll.return_value = {**self.identity, "paired": False, "pairingCode": "00123456"}
        output = io.StringIO()
        with patch.dict("os.environ", self.env, clear=True), patch("report1.prepare_device", return_value=self.client), \
                patch("report1.Reporter") as reporter, contextlib.redirect_stdout(output):
            self.assertEqual(0, main(["--pair"]))
        self.assertIn("00123456", output.getvalue())
        self.assertNotIn(self.client.token, output.getvalue())
        reporter.assert_not_called()
        self.client.wait_paired.assert_not_called()

    def test_revocation_owner_change_and_status_failure_block_http(self):
        config, _ = resolve_config(self.env, self.factory)
        api = ReportApi(config, lambda: require_same_owner(self.client, config.user_no))
        api.opener = Mock()
        for changed in ({"paired": False}, {"userNo": 99}, {"role": "CAMERA"}):
            self.client.refresh.return_value = {**self.identity, **changed}
            with self.subTest(changed=changed), self.assertRaises(ApiError):
                api._request("GET", "/api/logs")
        self.client.refresh.side_effect = RuntimeError("private-token-must-not-be-output")
        with self.assertRaises(ApiError) as caught:
            api._request("POST", "/api/reports/generated", {})
        self.assertNotIn("private-token", str(caught.exception))
        api.opener.open.assert_not_called()


if __name__ == "__main__":
    unittest.main()
