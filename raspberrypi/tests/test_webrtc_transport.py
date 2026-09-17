import asyncio
import datetime as dt
import json
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, patch

from test_runtime import ROOT, dependency_stubs, load_program
from pi_runtime import LatestFrame, Settings
from webrtc_transport import SignalClient, WebRtcWorker, SDP_LIMIT, STUN_URL, valid_sdp


FIRST = "00000000-0000-0000-0000-000000000001"
SECOND = "00000000-0000-0000-0000-000000000002"


class FakeTrack:
    kind = "video"
    def __init__(self):
        self.readyState = "live"
    def stop(self):
        self.readyState = "ended"


class FakePeer:
    def __init__(self, config):
        self.config = config
        self.connectionState = "new"
        self.closed = False
        self.transceiver = SimpleNamespace(kind="video", sender=object(), direction=None)
        self.localDescription = SimpleNamespace(type="answer", sdp="v=0\r\nanswer")
        self.events = {}
    def on(self, name):
        def register(callback):
            self.events[name] = callback
            return callback
        return register
    async def setRemoteDescription(self, description):
        self.remote = description
    async def setLocalDescription(self, description):
        pass
    async def createAnswer(self):
        return self.localDescription
    def getTransceivers(self):
        return [self.transceiver]
    def addTrack(self, track):
        self.track = track
        return self.transceiver.sender
    async def close(self):
        self.closed = True
        self.connectionState = "closed"


def runtime():
    return SimpleNamespace(
        RTCConfiguration=lambda **kw: SimpleNamespace(**kw), RTCIceServer=lambda **kw: SimpleNamespace(**kw),
        RTCPeerConnection=Mock(side_effect=FakePeer), RTCSessionDescription=lambda **kw: SimpleNamespace(**kw),
        VideoStreamTrack=FakeTrack, MediaStreamError=RuntimeError,
        av=SimpleNamespace(CodecContext=SimpleNamespace(create=Mock(return_value=Mock()))),
    )


class SignalTests(unittest.TestCase):
    def test_runtime_failure_does_not_pair_or_touch_local_frames_and_redacts_errors(self):
        config = Settings({}, ROOT)
        config.activate_pairing = Mock()
        frames, logger = Mock(), Mock()
        worker = WebRtcWorker(config, frames, logger=logger)
        with patch("webrtc_transport.load_runtime", side_effect=ModuleNotFoundError("private-sdp-token")):
            worker.run()
        self.assertEqual(worker.status()["state"], "unavailable")
        self.assertFalse(worker.status()["runtimeAvailable"])
        config.activate_pairing.assert_not_called()
        frames.get.assert_not_called()
        self.assertNotIn("private-sdp-token", str(logger.call_args_list))

    def test_camera_starts_only_direct_worker_and_exposes_safe_readiness(self):
        camera = load_program("fin_camera.py", dependency_stubs())
        worker = Mock()
        worker.status.return_value = {"transport": "webrtc", "runtimeAvailable": True}
        camera.video_transport = worker
        with patch.object(camera, "poll_capture_queue") as photo, patch.object(camera, "upload_event_image") as upload:
            camera.start_cloud_transport()
            worker.run.assert_called_once()
            photo.assert_not_called()
            upload.assert_not_called()
            self.assertEqual(camera.video_status(), {"transport": "webrtc", "runtimeAvailable": True})
        self.assertFalse(hasattr(camera, "upload_to_cloud_websocket"))

    def test_signaling_uses_reused_verified_https_session_without_redirects_or_images(self):
        config = Settings({}, ROOT)
        config.auth_headers = Mock(return_value={"Authorization": "Bearer private-test-value"})
        response = Mock(status_code=200)
        response.iter_content.return_value = [b'{"status":"PENDING"}']
        context = Mock()
        context.__enter__ = Mock(return_value=response)
        context.__exit__ = Mock(return_value=False)
        session = Mock()
        session.request.return_value = context
        client = SignalClient(config, session)
        self.assertEqual(client.next_offer(), (200, {"status": "PENDING"}))
        response.status_code = 204
        self.assertEqual(client.answer(FIRST, "v=0\r\nprivate-sdp"), (204, None))
        self.assertEqual(session.request.call_count, 2)
        for call in session.request.call_args_list:
            self.assertTrue(call.args[1].startswith("https://"))
            self.assertIsNot(call.kwargs.get("verify", True), False)
            self.assertFalse(call.kwargs["allow_redirects"])
            self.assertEqual(call.kwargs["timeout"], (3, 5))
            self.assertEqual(call.kwargs["headers"]["Authorization"], "Bearer private-test-value")
        self.assertEqual(set(session.request.call_args.kwargs["json"]), {"type", "sdp"})
        with self.assertRaises(ValueError):
            client.status("../../other-owner")

    def test_configuration_sdp_limits_and_https_only(self):
        self.assertEqual(Settings({}, ROOT).webrtc_fps, 12)
        self.assertEqual(Settings({"CAMERA_CAPTURE_FPS": "5", "CAMERA_CLOUD_FPS": "5"}, ROOT).webrtc_fps, 5)
        for invalid in ("0", "21", "13.5", "nan"):
            with self.assertRaises(ValueError):
                Settings({"WEBRTC_FPS": invalid}, ROOT)
        with self.assertRaises(ValueError):
            valid_sdp("v=0" + "한" * (SDP_LIMIT // 2))
        with self.assertRaises(ValueError):
            SignalClient(Settings({"RENDER_SERVER_URL": "http://example.invalid"}, ROOT), Mock())


class WorkerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.now = 0.0
        self.epoch = 1800000000
        self.client = Mock()
        self.client.answer.return_value = (204, None)
        self.client.next_offer.return_value = (204, None)
        self.logger = Mock()
        self.runtime = runtime()
        self.worker = WebRtcWorker(Settings({}, ROOT), LatestFrame(), self.runtime, self.client,
                                   logger=self.logger, clock=lambda: self.now, wall_clock=lambda: self.epoch + self.now)

    async def asyncTearDown(self):
        await self.worker.close_current("stopped")

    def expiry(self, delta):
        return dt.datetime.fromtimestamp(self.epoch + self.now + delta, dt.timezone.utc).isoformat()

    def offer(self, identifier=FIRST, delta=45):
        return {"sessionId": identifier, "type": "offer", "sdp": "v=0\r\nprivate-offer", "expiresAt": self.expiry(delta)}

    async def accept(self, identifier=FIRST):
        await self.worker.accept_offer(self.offer(identifier))
        state = self.worker.current
        await state.negotiation
        return state

    async def test_duplicate_pending_offer_negotiates_once_and_stun_has_no_turn(self):
        state = await self.accept()
        await self.worker.accept_offer(self.offer())
        self.assertIs(self.worker.current, state)
        self.assertEqual(self.runtime.RTCPeerConnection.call_count, 1)
        self.client.answer.assert_called_once()
        ice = state.peer.config.iceServers
        self.assertEqual(len(ice), 1)
        self.assertEqual(ice[0].urls, [STUN_URL])
        self.assertEqual(state.peer.transceiver.direction, "sendonly")
        self.assertNotIn(FIRST, str(self.logger.call_args_list))
        self.assertNotIn("private-offer", str(self.logger.call_args_list))

    async def test_new_offer_closes_previous_peer_and_stale_status_cannot_replace_it(self):
        first = await self.accept()
        second = await self.accept(SECOND)
        self.assertTrue(first.peer.closed)
        self.assertEqual(first.track.readyState, "ended")
        await self.worker.close_current("expired", expected=first)
        self.assertIs(self.worker.current, second)
        self.assertFalse(second.peer.closed)

    async def test_closed_and_unlinked_session_immediately_stop_video(self):
        for value in ("CLOSED", "EXPIRED"):
            with self.subTest(value=value):
                self.worker.seen.clear()
                state = await self.accept()
                await self.worker.apply_status(state, {"sessionId": FIRST, "status": value, "expiresAt": self.expiry(0)})
                self.assertIsNone(self.worker.current)
                self.assertTrue(state.peer.closed)
        for code in (401, 403, 404):
            with self.subTest(code=code):
                self.worker.seen.clear()
                state = await self.accept()
                state.checked_at = self.now
                self.client.next_offer.return_value = (code, None)
                await self.worker.poll_once()
                self.assertIsNone(self.worker.current)
                self.assertTrue(state.peer.closed)

    async def test_cloud_failure_cannot_extend_authorization_lease(self):
        state = await self.accept()
        state.expires_at = 200  # Isolate the independent 45-second authorization guard.
        self.now = 10
        self.client.status.side_effect = ConnectionError("private-proxy-secret")
        with self.assertRaises(ConnectionError):
            await self.worker.poll_once()
        self.assertEqual(state.confirmed_at, 0)
        self.now = 45
        await self.worker.check_lifetime()
        self.assertTrue(state.peer.closed)
        self.assertIsNone(self.worker.current)

    async def test_server_expiry_and_pending_hard_deadline_are_enforced(self):
        state = await self.accept()
        self.now = 20
        await self.worker.apply_status(state, {"sessionId": FIRST, "status": "PENDING", "expiresAt": self.expiry(500)})
        self.assertEqual(state.expires_at, 90)
        await self.worker.apply_status(state, {"sessionId": FIRST, "status": "READY", "expiresAt": self.expiry(30)})
        self.assertEqual(state.expires_at, 50)
        self.now = 50
        await self.worker.check_lifetime()
        self.assertTrue(state.peer.closed)

    async def test_expired_offer_never_creates_peer_and_rejected_answer_closes_it(self):
        await self.worker.accept_offer(self.offer(delta=-1))
        self.runtime.RTCPeerConnection.assert_not_called()
        self.client.answer.return_value = (409, None)
        await self.worker.accept_offer(self.offer())
        state = self.worker.current
        await state.negotiation
        self.assertIsNone(self.worker.current)
        self.assertTrue(state.peer.closed)

    async def test_signaling_marker_is_emitted_once_without_peer_or_video(self):
        await self.worker.poll_once()
        await self.worker.poll_once()
        self.assertTrue(self.worker.status()["signalingReady"])
        self.assertEqual(self.logger.call_args_list.count(unittest.mock.call("[webrtc] signaling ready")), 1)
        self.runtime.RTCPeerConnection.assert_not_called()

    async def test_lost_answer_ack_retries_identical_sdp_without_recreating_peer(self):
        self.client.answer.side_effect = [ConnectionError("private-proxy-secret"), (204, None)]
        with patch("webrtc_transport.asyncio.sleep", new=AsyncMock()):
            state = await self.accept()
        self.assertEqual(self.client.answer.call_count, 2)
        self.assertEqual(self.client.answer.call_args_list[0], self.client.answer.call_args_list[1])
        self.assertEqual(self.runtime.RTCPeerConnection.call_count, 1)
        self.assertFalse(state.peer.closed)
        self.assertNotIn("private-proxy-secret", str(self.logger.call_args_list))

    async def test_status_request_cannot_block_expiry_guard_or_resurrect_closed_peer(self):
        state = await self.accept()
        entered, release = threading.Event(), threading.Event()
        def blocked_status(_):
            entered.set()
            if not release.wait(3):
                raise TimeoutError()
            return 200, {"sessionId": FIRST, "status": "READY", "expiresAt": self.expiry(45)}
        self.client.status.side_effect = blocked_status
        self.now = 10
        poll = asyncio.create_task(self.worker.poll_once())
        try:
            self.assertTrue(await asyncio.to_thread(entered.wait, 2))
            self.now = 46
            await self.worker.check_lifetime()
            self.assertTrue(state.peer.closed)
        finally:
            release.set()
            await poll
        self.assertIsNone(self.worker.current)


if __name__ == "__main__":
    unittest.main()
