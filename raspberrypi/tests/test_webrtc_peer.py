"""Real in-process aiortc peers; synthetic JPEG, host candidates only, no cloud."""
import asyncio
import datetime as dt
from fractions import Fraction
import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pi_runtime import LatestFrame, Settings
from webrtc_transport import WebRtcWorker, load_runtime


@unittest.skipUnless(importlib.util.find_spec("aiortc") and importlib.util.find_spec("av"), "optional real aiortc runtime")
class RealPeerTests(unittest.IsolatedAsyncioTestCase):
    async def test_synthetic_camera_jpeg_reaches_real_peer_without_render_or_stun(self):
        runtime = load_runtime()
        from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
        source = runtime.av.VideoFrame(640, 480, "yuvj420p")
        for index, plane in enumerate(source.planes):
            plane.update(bytes([16 if index == 0 else 128]) * plane.buffer_size)
        encoder = runtime.av.CodecContext.create("mjpeg", "w")
        encoder.width, encoder.height = 640, 480
        encoder.pix_fmt, encoder.time_base = "yuvj420p", Fraction(1, 15)
        jpeg = b"".join(bytes(packet) for packet in encoder.encode(source) + encoder.encode(None))
        frames = LatestFrame()
        loop = asyncio.get_running_loop()
        answer_ready = asyncio.Event()
        answers = []
        received = loop.create_future()

        def answer(identifier, sdp):
            answers.append(sdp)
            loop.call_soon_threadsafe(answer_ready.set)
            return 204, None

        # Explicitly replace only the peer factory in this test: never contact Google STUN.
        runtime.RTCPeerConnection = lambda config: RTCPeerConnection(RTCConfiguration(iceServers=[]))
        client = SimpleNamespace(answer=answer)
        worker = WebRtcWorker(Settings({}, Path(__file__).parent), frames, runtime=runtime, client=client, logger=lambda _: None)
        viewer = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        viewer.addTransceiver("video", direction="recvonly")

        @viewer.on("track")
        async def on_track(track):
            try:
                frame = await track.recv()
                if not received.done():
                    received.set_result(frame)
            except Exception as error:
                if not received.done():
                    received.set_exception(error)

        async def produce():
            while True:
                frames.put(jpeg)
                await asyncio.sleep(1 / 15)

        producer = asyncio.create_task(produce())
        try:
            await viewer.setLocalDescription(await viewer.createOffer())
            await worker.accept_offer({
                "sessionId": "00000000-0000-0000-0000-000000000001", "type": "offer", "sdp": viewer.localDescription.sdp,
                "expiresAt": (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=45)).isoformat(),
            })
            await asyncio.wait_for(answer_ready.wait(), 15)
            await viewer.setRemoteDescription(RTCSessionDescription(type="answer", sdp=answers[0]))
            frame = await asyncio.wait_for(received, 15)
            self.assertEqual((frame.width, frame.height), (640, 480))
            self.assertEqual(viewer.connectionState, "connected")
            self.assertEqual(worker.current.peer.connectionState, "connected")
            self.assertGreater(worker.status()["framesSent"], 0)
            self.assertNotIn(" typ relay", answers[0])
            self.assertNotIn(" typ srflx", answers[0])
        finally:
            producer.cancel()
            await asyncio.gather(producer, return_exceptions=True)
            await worker.close_current("stopped")
            await viewer.close()
