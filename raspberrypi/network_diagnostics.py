"""Read-only network diagnosis. Never claim tasks, upload images or print secrets."""
import argparse
import datetime
import http.client
import ipaddress
import json
import os
import queue
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

from pi_runtime import Settings


def endpoint(url):
    parsed = urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("Expected an HTTP(S) endpoint")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("Credentials in endpoint URLs are not supported by this diagnostic")
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    return {"scheme": parsed.scheme, "host": parsed.hostname, "port": port}


def safe_error(error):
    """Exception strings may contain proxy credentials, so never emit them."""
    reason = error.reason if isinstance(error, urllib.error.URLError) else error
    result = {"error_type": type(reason).__name__}
    code = getattr(reason, "errno", None)
    if isinstance(code, int):
        result["errno"] = code
    if isinstance(reason, ssl.SSLCertVerificationError):
        result["certificate_verification_failed"] = True
        result["verify_code"] = reason.verify_code
    return result


def probe(function, timeout):
    """Bound DNS as well as socket waits; a hung resolver cannot hang the script."""
    results = queue.Queue(maxsize=1)
    started = time.monotonic()
    def work():
        try:
            results.put({"status": "ok", "details": function()})
        except Exception as error:
            results.put({"status": "error", **safe_error(error)})
    threading.Thread(target=work, daemon=True).start()
    try:
        result = results.get(timeout=timeout)
    except queue.Empty:
        result = {"status": "timeout"}
    result["elapsed_ms"] = round((time.monotonic() - started) * 1000)
    return result


def environment_summary(environ=None):
    env = os.environ if environ is None else environ
    names = ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "all_proxy", "no_proxy", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "SSL_CERT_FILE", "SSL_CERT_DIR")
    # Only variable presence is printed, never values, credentials or local paths.
    result = {name: bool(env.get(name)) for name in names}
    result["STREAM_URL_overridden"] = bool(env.get("STREAM_URL"))
    result["RENDER_SERVER_URL_overridden"] = bool(env.get("RENDER_SERVER_URL"))
    return result


def ca_context():
    ca_file = os.environ.get("REQUESTS_CA_BUNDLE") or os.environ.get("CURL_CA_BUNDLE")
    return ssl.create_default_context(cafile=ca_file or None)


def resolve_host(target):
    results = socket.getaddrinfo(target["host"], target["port"], socket.AF_UNSPEC, socket.SOCK_STREAM)
    addresses = []
    seen = set()
    family_count = {}
    for family, _, _, _, address in results:
        key = (family, address[0])
        if family not in (socket.AF_INET, socket.AF_INET6) or key in seen:
            continue
        seen.add(key)
        family_count[family] = family_count.get(family, 0) + 1
        if family_count[family] > 2:
            continue
        addresses.append({"family": family, "ip": address[0], "port": target["port"]})
    if not addresses:
        raise socket.gaierror("No IPv4/IPv6 addresses")
    return addresses


def tcp_probe(address, timeout):
    with socket.socket(address["family"], socket.SOCK_STREAM) as connection:
        connection.settimeout(timeout)
        connection.connect((address["ip"], address["port"]))
    return {"connected": True}


def tls_probe(target, address, timeout):
    context = ca_context()
    with socket.socket(address["family"], socket.SOCK_STREAM) as connection:
        connection.settimeout(timeout)
        connection.connect((address["ip"], address["port"]))
        with context.wrap_socket(connection, server_hostname=target["host"]) as secure:
            return {"verified": True, "tls_version": secure.version()}


def camera_head(target, timeout):
    # Local camera is checked directly, separately from cloud proxy settings.
    if target["scheme"] == "https":
        connection = http.client.HTTPSConnection(target["host"], target["port"], timeout=timeout, context=ca_context())
    else:
        connection = http.client.HTTPConnection(target["host"], target["port"], timeout=timeout)
    try:
        connection.request("HEAD", "/snapshot")
        response = connection.getresponse()
        return {"http_status": response.status, "camera_frame_ready": response.status == 200}
    finally:
        connection.close()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def cloud_head(target, timeout):
    # urllib honors the current proxy environment; no proxy or DNS configuration changes.
    host = target["host"]
    authority = f"[{host}]" if ":" in host else host
    url = f"{target['scheme']}://{authority}:{target['port']}/"
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ca_context()))
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "DoorPiNetworkDiagnostic/1"})
    try:
        with opener.open(request, timeout=timeout) as response:
            return {"http_status": response.status, "http_response_received": True}
    except urllib.error.HTTPError as error:
        # Even 404/401 proves HTTP connectivity. Do not read an error body or headers.
        return {"http_status": error.code, "http_response_received": True}


def run_diagnostics(settings, timeout=5):
    cloud = endpoint(settings.server_url)
    camera = endpoint(settings.stream_url)
    try:
        local_loopback = ipaddress.ip_address(camera["host"]).is_loopback
    except ValueError:
        local_loopback = camera["host"].lower() == "localhost"
    report = {
        "utc_now": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "python_version": sys.version.split()[0],
        "environment_presence_only": environment_summary(),
        "cloud_endpoint": cloud,
        "camera_endpoint": {**camera, "loopback": local_loopback},
        "notes": [
            "No DNS, VPN, proxy, certificate, service or database settings are changed.",
            "No task endpoints, uploads, camera image bodies or WebSocket producer connections are used.",
            "TLS uses Python's trust store, or REQUESTS_CA_BUNDLE/CURL_CA_BUNDLE when set; Requests' installed CA bundle can differ.",
            "Direct TCP/TLS probes and the HTTP probe using configured proxy settings are reported separately.",
            "The HTTP probe uses urllib proxy support; ALL_PROXY/SOCKS and websocket-client behavior may differ from this standard-library check.",
            "An HTTP response proves reachability; it does not prove database or face-task health.",
        ],
    }
    report["camera_snapshot_head"] = probe(lambda: camera_head(camera, timeout), timeout + 1)
    report["cloud_system_dns"] = probe(lambda: resolve_host(cloud), timeout + 1)
    connections = []
    if report["cloud_system_dns"]["status"] == "ok":
        for address in report["cloud_system_dns"]["details"]:
            item = {"ip": address["ip"], "family": "IPv6" if address["family"] == socket.AF_INET6 else "IPv4"}
            item["tcp"] = probe(lambda address=address: tcp_probe(address, timeout), timeout + 1)
            if item["tcp"]["status"] == "ok" and cloud["scheme"] == "https":
                item["tls"] = probe(lambda address=address: tls_probe(cloud, address, timeout), timeout + 1)
            connections.append(item)
    report["cloud_direct_connections"] = connections
    report["cloud_http_with_current_proxy_settings"] = probe(lambda: cloud_head(cloud, timeout), timeout + 1)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=float, default=5, help="Per-probe timeout in seconds (1-15, default 5)")
    arguments = parser.parse_args()
    if not 1 <= arguments.timeout <= 15:
        parser.error("--timeout must be between 1 and 15")
    try:
        report = run_diagnostics(Settings(), arguments.timeout)
    except Exception as error:
        report = {"status": "configuration_error", **safe_error(error)}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
