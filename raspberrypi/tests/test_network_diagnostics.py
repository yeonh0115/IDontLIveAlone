import json
from pathlib import Path
import socket
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import network_diagnostics as diagnostics
from pi_runtime import Settings


class NetworkDiagnosticsTests(unittest.TestCase):
    def test_proxy_and_error_secrets_are_not_printed(self):
        env = {"HTTPS_PROXY": "http://account:secret-password@proxy.invalid:8080", "REQUESTS_CA_BUNDLE": "/private/secret-path.pem"}
        summary = diagnostics.environment_summary(env)
        failure = diagnostics.safe_error(urllib.error.URLError("http://account:secret-password@proxy.invalid"))
        output = json.dumps({"summary": summary, "failure": failure})
        self.assertNotIn("secret-password", output)
        self.assertNotIn("secret-path", output)
        self.assertNotIn("account", output)
        self.assertTrue(summary["HTTPS_PROXY"])
        self.assertEqual(failure, {"error_type": "str"})

    def test_endpoint_omits_path_query_and_rejects_embedded_credentials(self):
        self.assertEqual(diagnostics.endpoint("https://example.test/private?token=hidden"), {"scheme": "https", "host": "example.test", "port": 443})
        with self.assertRaises(ValueError):
            diagnostics.endpoint("https://account:secret@example.test")

    def test_camera_check_only_heads_snapshot(self):
        connection = Mock()
        connection.getresponse.return_value = SimpleNamespace(status=503)
        with patch.object(diagnostics.http.client, "HTTPConnection", return_value=connection):
            result = diagnostics.camera_head({"scheme": "http", "host": "127.0.0.1", "port": 5002}, 5)
        connection.request.assert_called_once_with("HEAD", "/snapshot")
        self.assertFalse(result["camera_frame_ready"])
        connection.close.assert_called_once()

    def test_http_404_is_reachability_and_no_body_is_read(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError("https://example.test/", 404, "not found", {}, None)
        with patch.object(diagnostics.urllib.request, "build_opener", return_value=opener), patch.object(diagnostics, "ca_context", return_value=Mock()):
            result = diagnostics.cloud_head({"scheme": "https", "host": "example.test", "port": 443}, 5)
        request = opener.open.call_args.args[0]
        self.assertEqual(request.method, "HEAD")
        self.assertEqual(request.full_url, "https://example.test:443/")
        self.assertEqual(result, {"http_status": 404, "http_response_received": True})

    def test_local_camera_and_cloud_dns_failure_are_independent(self):
        config = Settings({}, ROOT)
        with patch.object(diagnostics, "camera_head", return_value={"http_status": 200, "camera_frame_ready": True}), patch.object(diagnostics, "resolve_host", side_effect=socket.gaierror(-2, "name resolution failed")), patch.object(diagnostics, "cloud_head", return_value={"http_status": 200, "http_response_received": True}), patch.object(diagnostics, "tcp_probe") as tcp:
            report = diagnostics.run_diagnostics(config, timeout=1)
        self.assertEqual(report["camera_snapshot_head"]["status"], "ok")
        self.assertEqual(report["cloud_system_dns"]["error_type"], "gaierror")
        self.assertEqual(report["cloud_http_with_current_proxy_settings"]["status"], "ok")
        self.assertTrue(report["camera_endpoint"]["loopback"])
        tcp.assert_not_called()


if __name__ == "__main__":
    unittest.main()
