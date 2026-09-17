package com.example.na_honja_ansanda;

import com.example.na_honja_ansanda.data.video.RtcViewerState;
import org.junit.Test;
import static org.junit.Assert.*;

public class RtcViewerStateTest {
    private final RtcViewerState state = new RtcViewerState();
    private static final String SESSION = "6a4a4593-054a-4ad9-88ea-ab304d6b0415";
    private static final String SDP = "v=0\r\ns=-\r\n";
    private long pending() {
        long id = state.start(0);
        assertTrue(state.pageReady(id, 1));
        assertTrue(state.offer(id, SDP, 2));
        assertTrue(state.created(id, SESSION, 3));
        return id;
    }
    @Test public void onlyOneOfferPerLoadedPage() {
        long id = state.start(0);
        assertFalse(state.offer(id, SDP, 1));
        assertTrue(state.pageReady(id, 2));
        assertFalse(state.pageReady(id, 3));
        assertTrue(state.offer(id, SDP, 4));
        assertFalse(state.offer(id, SDP, 5));
    }
    @Test public void answerPollingStopsAtFortyRequests() {
        long id = pending();
        for (int count = 0; count < 40; count++) assertTrue(state.poll(id));
        assertFalse(state.poll(id));
    }
    @Test public void answerStopsPollingAndEnablesLeaseRenewal() {
        long id = pending();
        assertFalse(state.canLease(id));
        assertTrue(state.answer(id, "answer", SDP, 4));
        assertFalse(state.poll(id));
        assertTrue(state.canLease(id));
        assertTrue(state.playing(id));
        assertFalse(state.expired(100000));
    }
    @Test public void cannotReportPlayingBeforeNativeAcceptsAnAnswer() {
        long id = pending();
        assertFalse(state.playing(id));
        assertFalse(state.answer(id, "offer", SDP, 4));
        assertFalse(state.answer(id, "answer", "invalid", 4));
        assertEquals(RtcViewerState.Phase.POLLING, state.phase());
    }
    @Test public void stopInvalidatesQueuedJsAndHttpCallbacks() {
        long old = pending(); state.stop();
        assertFalse(state.answer(old, "answer", SDP, 5));
        assertFalse(state.fail(old));
        assertFalse(state.canLease(old));
        long next = state.start(10);
        assertFalse(state.pageReady(old, 11));
        assertTrue(state.pageReady(next, 11));
        assertFalse(state.created(old, SESSION, 12));
    }
    @Test public void failedViewerCannotRenewOrResumeWithoutExplicitStart() {
        long id = pending(); assertTrue(state.fail(id));
        assertFalse(state.poll(id)); assertFalse(state.canLease(id));
        assertFalse(state.pageReady(id, 9)); assertFalse(state.playing(id));
        assertFalse(state.expired(100000));
        assertTrue(state.start(100001) > id);
    }
    @Test public void pageAndIceGatheringAreTimeBounded() {
        long id = state.start(0);
        assertFalse(state.expired(9999)); assertTrue(state.expired(10000));
        state.pageReady(id, 20);
        assertFalse(state.expired(15019)); assertTrue(state.expired(15020));
    }
    @Test public void pendingAndConnectionCannotHangForever() {
        long id = pending();
        assertFalse(state.expired(60002)); assertTrue(state.expired(60003));
        state.answer(id, "answer", SDP, 100);
        assertFalse(state.expired(20099)); assertTrue(state.expired(20100));
    }
    @Test public void initialCreateAllowsColdStartButStillHasAHardDeadline() {
        long id = state.start(0); state.pageReady(id, 1); state.offer(id, SDP, 2);
        assertFalse(state.expired(142097)); // Observed production process startup.
        assertFalse(state.expired(185001));
        assertTrue(state.expired(185002));
        state.stop();
        id = state.start(90000); state.pageReady(id, 90001); state.offer(id, SDP, 90002);
        assertTrue(state.created(id, SESSION, 232097));
        assertTrue(state.poll(id));
    }
    @Test public void sessionIdCannotSelectAnArbitraryApiPath() {
        assertTrue(RtcViewerState.validSessionId(SESSION));
        assertFalse(RtcViewerState.validSessionId("../../users"));
        assertFalse(RtcViewerState.validSessionId("https://example.com"));
        assertFalse(RtcViewerState.validSessionId("1-1-1-1-1"));
        assertFalse(RtcViewerState.validSessionId(null));
    }
    @Test public void sdpLimitCountsUtf8Bytes() {
        assertTrue(RtcViewerState.validSdp(SDP));
        assertFalse(RtcViewerState.validSdp(""));
        assertFalse(RtcViewerState.validSdp(null));
        assertFalse(RtcViewerState.validSdp("v=0" + "가".repeat(22000)));
        assertTrue(RtcViewerState.validSdp("v=0" + "a".repeat(65533)));
        assertFalse(RtcViewerState.validSdp("v=0" + "a".repeat(65534)));
    }
    @Test public void relayCandidateIsNeverAcceptedAsDirectVideo() {
        assertTrue(RtcViewerState.directCandidate("host"));
        assertTrue(RtcViewerState.directCandidate("srflx"));
        assertTrue(RtcViewerState.directCandidate("prflx"));
        assertFalse(RtcViewerState.directCandidate("relay"));
        assertFalse(RtcViewerState.directCandidate(""));
        assertFalse(RtcViewerState.directCandidate(null));
    }
}
