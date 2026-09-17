"""Direct, video-only WebRTC with authenticated HTTPS signaling.

Importing this module does not import codecs, connect to a server or open hardware.
Local capture and face recognition remain independent when WebRTC is unavailable.
"""
import asyncio
from collections import OrderedDict
from dataclasses import dataclass
import datetime as dt
from fractions import Fraction
import json
import threading
import time
from types import SimpleNamespace
from urllib.parse import urlparse
import uuid

from pi_runtime import MAX_STREAM_JPEG_BYTES

STUN_URL = "stun:stun.l.google.com:19302"
SDP_LIMIT = 64 * 1024
SIGNAL_BODY_LIMIT = SDP_LIMIT + 8192
AUTH_LEASE_SECONDS = 45


def load_runtime():
    import av
    from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription, VideoStreamTrack
    from aiortc.mediastreams import MediaStreamError
    return SimpleNamespace(
        av=av, RTCConfiguration=RTCConfiguration, RTCIceServer=RTCIceServer,
        RTCPeerConnection=RTCPeerConnection, RTCSessionDescription=RTCSessionDescription,
        VideoStreamTrack=VideoStreamTrack, MediaStreamError=MediaStreamError,
    )


def session_id(value):
    if not isinstance(value, str):
        raise ValueError("Invalid session identifier")
    parsed = str(uuid.UUID(value))
    if parsed != value.lower():
        raise ValueError("Invalid session identifier")
    return parsed


def valid_sdp(value):
    if not isinstance(value, str) or not value.startswith("v=0") or len(value.encode("utf-8")) > SDP_LIMIT:
        raise ValueError("Invalid SDP")
    return value


def expiry_delay(value, wall_clock=time.time):
    if not isinstance(value, str):
        raise ValueError("Missing session expiry")
    expiry = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if expiry.tzinfo is None:
        raise ValueError("Session expiry needs a timezone")
    return expiry.timestamp() - wall_clock()


class SignalClient:
    """One reusable Session; serialized calls also protect requests cookie/pool state."""
    def __init__(self, settings, session=None):
        if urlparse(settings.server_url).scheme != "https":
            raise ValueError("WebRTC signaling requires HTTPS")
        if session is None:
            import requests
            session = requests.Session()
        self.settings, self.session = settings, session
        self.lock = threading.Lock()

    def request(self, method, path, payload=None):
        with self.lock:
            with self.session.request(
                method, self.settings.api_url(path), json=payload,
                headers=self.settings.auth_headers(), timeout=(3, 5),
                allow_redirects=False, stream=True,
            ) as response:
                code = response.status_code
                if code != 200:
                    return code, None
                body = bytearray()
                for part in response.iter_content(chunk_size=8192):
                    body.extend(part)
                    if len(body) > SIGNAL_BODY_LIMIT:
                        raise ValueError("Signaling response too large")
                return code, json.loads(body.decode("utf-8"))

    def next_offer(self):
        return self.request("GET", "/api/rtc/camera/next")

    def status(self, identifier):
        return self.request("GET", f"/api/rtc/camera/sessions/{session_id(identifier)}")

    def answer(self, identifier, sdp):
        return self.request("POST", f"/api/rtc/camera/sessions/{session_id(identifier)}/answer",
                            {"type": "answer", "sdp": valid_sdp(sdp)})

    def close(self):
        with self.lock:
            self.session.close()


def create_camera_track(runtime, frames, settings, on_frame=lambda: None):
    """Decode only the newest JPEG off the asyncio thread; aiortc handles encoding."""
    class SharedCameraTrack(runtime.VideoStreamTrack):
        def __init__(self):
            super().__init__()
            self.decoder = runtime.av.CodecContext.create("mjpeg", "r")
            self.last_sequence = -1
            self.next_frame_at = 0.0
            self.started_at = time.monotonic()
            self.last_pts = -1

        def decode(self, jpeg):
            decoded = self.decoder.decode(runtime.av.Packet(jpeg))
            if not decoded:
                raise ValueError("Empty decoded frame")
            frame = decoded[0]
            if not 1 <= frame.width <= 1280 or not 1 <= frame.height <= 720:
                raise ValueError("Unsupported camera dimensions")
            return frame

        async def recv(self):
            while self.readyState == "live":
                delay = self.next_frame_at - time.monotonic()
                if delay > 0:
                    await asyncio.sleep(delay)
                jpeg, sequence = frames.get(max_age=settings.camera_max_frame_age)
                if jpeg is None or sequence == self.last_sequence:
                    await asyncio.sleep(0.02)
                    continue
                self.last_sequence = sequence
                if not 4 < len(jpeg) <= MAX_STREAM_JPEG_BYTES:
                    continue
                try:
                    frame = await asyncio.to_thread(self.decode, jpeg)
                except Exception:
                    # A damaged frame must not terminate local capture or leak codec input.
                    continue
                if self.readyState != "live":
                    break
                self.last_pts = max(self.last_pts + 1, int((time.monotonic() - self.started_at) * 90000))
                frame.pts, frame.time_base = self.last_pts, Fraction(1, 90000)
                self.next_frame_at = time.monotonic() + 1 / settings.webrtc_fps
                on_frame()
                return frame
            raise runtime.MediaStreamError

    return SharedCameraTrack()


@dataclass
class ActiveSession:
    identifier: str
    peer: object
    track: object
    started_at: float
    expires_at: float
    confirmed_at: float
    checked_at: float
    negotiation: object = None
    ready_deadline: float = None


class WebRtcWorker:
    def __init__(self, settings, frames, runtime=None, client=None, logger=print,
                 clock=time.monotonic, wall_clock=time.time):
        self.settings, self.frames = settings, frames
        self.runtime, self.client = runtime, client
        self.logger, self.clock, self.wall_clock = logger, clock, wall_clock
        self.current = None
        self.seen = OrderedDict()
        self.stop_event = threading.Event()
        self.state_lock = threading.Lock()
        self.state = {"transport": "webrtc", "state": "starting", "error": None,
                      "fps": settings.webrtc_fps, "framesSent": 0, "turnConfigured": False,
                      "runtimeAvailable": False, "signalingReady": False}
        self.signaling_announced = False

    def status(self):
        with self.state_lock:
            return dict(self.state)

    def set_state(self, state, error=None):
        with self.state_lock:
            changed = self.state["state"] != state or self.state["error"] != error
            self.state.update(state=state, error=error)
        if changed:
            self.logger(f"[webrtc] {state}" + (f": {error}" if error else ""))

    def frame_sent(self):
        with self.state_lock:
            self.state["framesSent"] += 1

    def signaling_ready(self, ready):
        with self.state_lock:
            self.state["signalingReady"] = ready
        if ready and not self.signaling_announced:
            self.signaling_announced = True
            self.logger("[webrtc] signaling ready")

    def stop(self):
        self.stop_event.set()

    def run(self):
        try:
            self.runtime = self.runtime or load_runtime()
        except Exception as error:
            self.set_state("unavailable", type(error).__name__)
            return
        with self.state_lock:
            self.state["runtimeAvailable"] = True
        try:
            self.set_state("waiting_pairing")
            self.settings.activate_pairing()
            if self.settings.device is None:
                self.set_state("unavailable", "PairedCameraRequired")
                return
            self.client = self.client or SignalClient(self.settings)
            asyncio.run(self.run_async())
        except Exception as error:
            self.set_state("unavailable", type(error).__name__)

    async def run_async(self):
        self.set_state("waiting")
        guard = asyncio.create_task(self.guard())
        try:
            while not self.stop_event.is_set():
                try:
                    await self.poll_once()
                except Exception as error:
                    # Never print exception text, session UUIDs, bearer values or SDP.
                    self.set_state("signaling_unavailable", type(error).__name__)
                    self.signaling_ready(False)
                await asyncio.sleep(2)
        finally:
            guard.cancel()
            await asyncio.gather(guard, return_exceptions=True)
            await self.close_current("stopped")
            await asyncio.to_thread(self.client.close)

    async def guard(self):
        while not self.stop_event.is_set():
            await self.check_lifetime()
            await asyncio.sleep(0.2)

    async def check_lifetime(self):
        state = self.current
        if state is None:
            return
        now = self.clock()
        if now >= state.expires_at or now - state.confirmed_at >= AUTH_LEASE_SECONDS:
            await self.close_current("expired", expected=state)

    async def close_current(self, reason, expected=None):
        state = self.current
        if state is None or (expected is not None and state is not expected):
            return
        self.current = None  # Invalidate late HTTP and aiortc callbacks before any await.
        state.track.stop()
        task = state.negotiation
        if task is not None and task is not asyncio.current_task():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        try:
            await state.peer.close()
        finally:
            if self.current is None:
                self.set_state(reason)

    async def poll_once(self):
        state = self.current
        if state is not None and self.clock() - state.checked_at >= 10:
            state.checked_at = self.clock()
            code, body = await asyncio.to_thread(self.client.status, state.identifier)
            if self.current is state:
                if code in (401, 403, 404, 409):
                    await self.close_current("authorization_lost", expected=state)
                elif code == 200:
                    await self.apply_status(state, body)
                else:
                    self.set_state("signaling_unavailable", f"HTTP{code}")
        code, offer = await asyncio.to_thread(self.client.next_offer)
        if code == 200:
            await self.accept_offer(offer)
            self.signaling_ready(True)
        elif code == 204:
            self.signaling_ready(True)
        elif code in (401, 403, 404):
            await self.close_current("authorization_lost")
            self.set_state("authorization_lost", f"HTTP{code}")
            self.signaling_ready(False)
        elif code != 204:
            self.set_state("signaling_unavailable", f"HTTP{code}")
            self.signaling_ready(False)

    async def apply_status(self, state, body):
        if not isinstance(body, dict) or session_id(body.get("sessionId")) != state.identifier:
            raise ValueError("Invalid status response")
        status = body.get("status")
        if status in ("CLOSED", "EXPIRED"):
            await self.close_current(status.lower(), expected=state)
            return
        if status not in ("PENDING", "READY"):
            raise ValueError("Invalid session state")
        now = self.clock()
        if status == "READY" and state.ready_deadline is None:
            state.ready_deadline = now + 1800
        hard_deadline = state.ready_deadline or state.started_at + 90
        state.expires_at = min(now + expiry_delay(body.get("expiresAt"), self.wall_clock), hard_deadline)
        state.confirmed_at = now
        await self.check_lifetime()

    async def accept_offer(self, body):
        if not isinstance(body, dict) or body.get("type") != "offer":
            raise ValueError("Invalid offer response")
        identifier = session_id(body.get("sessionId"))
        sdp = valid_sdp(body.get("sdp"))
        now = self.clock()
        duration = min(90, expiry_delay(body.get("expiresAt"), self.wall_clock))
        if duration <= 0:
            return
        for old, expires in list(self.seen.items()):
            if expires <= now:
                self.seen.pop(old, None)
        if identifier in self.seen or (self.current and identifier == self.current.identifier):
            return
        await self.close_current("replaced")
        self.seen[identifier] = now + 90
        while len(self.seen) > 32:
            self.seen.popitem(last=False)
        config = self.runtime.RTCConfiguration(iceServers=[self.runtime.RTCIceServer(urls=[STUN_URL])])
        track = create_camera_track(self.runtime, self.frames, self.settings, self.frame_sent)
        try:
            peer = self.runtime.RTCPeerConnection(config)
        except Exception:
            track.stop()
            raise
        state = ActiveSession(identifier, peer, track, now, now + duration, now, now)
        self.current = state

        @peer.on("connectionstatechange")
        async def connection_changed():
            if self.current is not state:
                return
            if peer.connectionState == "connected":
                self.set_state("connected")
            elif peer.connectionState in ("failed", "closed", "disconnected"):
                asyncio.create_task(self.close_current("disconnected", expected=state))

        self.set_state("negotiating")
        state.negotiation = asyncio.create_task(self.negotiate(state, sdp))

    async def negotiate(self, state, sdp):
        try:
            async with asyncio.timeout(90):
                await self.make_answer(state, sdp)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            if self.current is state:
                self.set_state("negotiation_failed", type(error).__name__)
                await self.close_current("negotiation_failed", expected=state)

    async def make_answer(self, state, sdp):
        peer = state.peer
        await peer.setRemoteDescription(self.runtime.RTCSessionDescription(sdp=sdp, type="offer"))
        if not any(transceiver.kind == "video" for transceiver in peer.getTransceivers()):
            raise ValueError("Offer has no video receiver")
        sender = peer.addTrack(state.track)
        for transceiver in peer.getTransceivers():
            transceiver.direction = "sendonly" if transceiver.sender is sender else "inactive"
        await peer.setLocalDescription(await peer.createAnswer())
        answer = valid_sdp(peer.localDescription.sdp)
        # setLocalDescription awaits ICE gathering in aiortc: send full SDP, no trickle.
        while self.current is state and not self.stop_event.is_set():
            await self.check_lifetime()
            if self.current is not state:
                return
            try:
                code, _ = await asyncio.to_thread(self.client.answer, state.identifier, answer)
            except Exception as error:
                self.set_state("signaling_unavailable", type(error).__name__)
                code = None
            if self.current is not state:
                return
            if code == 204:
                state.checked_at = self.clock() - 10  # Confirm the authoritative READY expiry promptly.
                if peer.connectionState != "connected":
                    self.set_state("connecting")
                return
            if code in (400, 401, 403, 404, 409, 410, 422):
                await self.close_current("answer_rejected", expected=state)
                return
            await asyncio.sleep(2)
