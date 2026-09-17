"""Pi B STM32 UART sensor receiver; server owns all MySQL writes."""
import json
import os
import ssl
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from event_runtime import Config, DeliveryWorker, EventGate, EventOutbox, event_key, make_event

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None

class ApiClient:
    def __init__(self, config):
        self.config = config
        ca_file = os.environ.get("REQUESTS_CA_BUNDLE") or os.environ.get("CURL_CA_BUNDLE")
        context = ssl.create_default_context(cafile=ca_file or None)
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=context))

    def send(self, request, timeout):
        try:
            with self.opener.open(request, timeout=timeout) as response:
                data = response.read(65537)
                if len(data) > 65536:
                    return response.status, None
                try:
                    return response.status, json.loads(data.decode("utf-8"))
                except (ValueError, UnicodeError):
                    return response.status, None
        except urllib.error.HTTPError as error:
            # Never log authentication headers, endpoint query strings or response bodies.
            return error.code, None

    def register(self, payload):
        request = urllib.request.Request(
            self.config.server_url + "/api/device/events",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"), method="POST",
            headers={"Content-Type": "application/json", "Authorization": "Bearer " + self.config.token},
        )
        return self.send(request, 10)

    def capture(self, identity, expected_user_no):
        parsed = urlsplit(self.config.camera_url)
        query = dict(parse_qsl(parsed.query))
        query.update({"log_id": identity, "expected_user_no": str(expected_user_no)})
        url = urlunsplit((parsed.scheme, parsed.netloc, parsed.path, urlencode(query), ""))
        # Bearer credentials go only to the HTTPS server, never to the LAN camera.
        return self.send(urllib.request.Request(url, method="GET"), 15)

def main():
    try:
        config = Config()
        config.activate_pairing()
        outbox = EventOutbox(config.data_dir)
        worker = DeliveryWorker(config, outbox, ApiClient(config))
    except Exception as error:
        print(f"[configuration] {type(error).__name__}; check USER_NO, DEVICE_API_TOKEN, URLs, CA and writable PI_B_DATA_DIR", flush=True)
        return 1
    import serial
    stop = threading.Event()
    thread = threading.Thread(target=worker.run, args=(stop,), daemon=True)
    thread.start()
    gate = EventGate()
    try:
        while not stop.is_set():
            try:
                with serial.Serial(config.uart_port, baudrate=config.baud_rate, timeout=1) as uart:
                    uart.reset_input_buffer()
                    print(f"[UART] STM32 GPIO14(TX)/15(RX), {config.baud_rate} baud; listening", flush=True)
                    while not stop.is_set():
                        if uart.in_waiting:
                            key = event_key(uart.readline().decode("utf-8", errors="ignore").strip())
                            now = time.monotonic()
                            if key and gate.accepts(key, now):
                                payload = make_event(key, config.user_no)
                                outbox.enqueue(payload, config.photo_max_delay)
                                gate.mark(key, now)  # Suppress repeats only after durable persistence.
                                print(f"[UART] {key} saved as event {payload['eventId']}", flush=True)
                        stop.wait(0.01)
            except Exception as error:
                print(f"[UART/storage] {type(error).__name__}; retrying in 3 seconds", flush=True)
                stop.wait(3)
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        thread.join(timeout=1)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
