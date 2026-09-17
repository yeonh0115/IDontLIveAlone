package com.example.na_honja_ansanda;

import com.example.na_honja_ansanda.data.video.ViewerStreamState;
import org.junit.Test;
import static org.junit.Assert.*;

public class ViewerStreamStateTest {
    private final ViewerStreamState state = new ViewerStreamState();
    private long connect(long time) {
        long id = state.start(time);
        assertTrue(state.opened(id, time));
        return id;
    }
    @Test public void onlyOneRequestUntilDecodedFrameIsPresented() {
        long id = connect(0);
        assertTrue(state.request(id, 0));
        assertFalse(state.request(id, 100));
        assertTrue(state.frame(id, 100));
        assertFalse(state.frame(id, 101));
        assertFalse(state.request(id, 200));
        assertTrue(state.decoded(id, 201));
        assertFalse(state.request(id, 300));
        assertTrue(state.present(id, 301));
        assertTrue(state.request(id, 301));
    }
    @Test public void frameRateNeverExceedsTenRequestsPerSecond() {
        long id = connect(0);
        assertTrue(state.request(id, 0));
        assertTrue(state.frame(id, 1));
        assertTrue(state.decoded(id, 2));
        assertTrue(state.present(id, 3));
        assertFalse(state.request(id, 99));
        assertTrue(state.request(id, 100));
    }
    @Test public void waitingDelaysNextRequestByOneHundredMilliseconds() {
        long id = connect(0);
        assertTrue(state.request(id, 0));
        assertTrue(state.waiting(id, 150));
        assertFalse(state.waiting(id, 160));
        assertFalse(state.request(id, 249));
        assertTrue(state.request(id, 250));
    }
    @Test public void responseDeadlineRejectsLateFrameAndRetriesAfterOneSecond() {
        long id = connect(0);
        state.request(id, 100);
        assertFalse(state.timedOut(2099));
        assertTrue(state.timedOut(2100));
        assertFalse(state.frame(id, 2100));
        assertFalse(state.waiting(id, 2100));
        assertTrue(state.failed(id, 2100, false));
        assertFalse(state.retryDue(3099));
        assertTrue(state.retryDue(3100));
        long replacement = state.reconnect(3100);
        assertTrue(replacement > id);
        assertFalse(state.opened(id, 3101));
        assertTrue(state.opened(replacement, 3101));
    }
    @Test public void connectionHandshakeAlsoHasABoundedDeadline() {
        state.start(25);
        assertFalse(state.timedOut(2024));
        assertTrue(state.timedOut(2025));
    }
    @Test public void oldFailureCannotCancelReplacementConnection() {
        long old = connect(0);
        assertTrue(state.failed(old, 10, false));
        long replacement = state.reconnect(1010);
        assertTrue(state.opened(replacement, 1011));
        assertFalse(state.failed(old, 1012, true));
        assertEquals(ViewerStreamState.Phase.READY, state.phase());
        assertTrue(state.request(replacement, 1013));
    }
    @Test public void authenticationFailureStopsAutomaticRetry() {
        long id = connect(0);
        assertTrue(state.failed(id, 1, true));
        assertEquals(ViewerStreamState.Phase.AUTH_REQUIRED, state.phase());
        assertFalse(state.retryDue(100000));
        assertFalse(state.request(id, 100000));
        assertFalse(state.timedOut(100000));
        assertTrue(state.displayIsStale(1));
    }
    @Test public void stoppedOrReplacedSessionIgnoresAllPriorCallbacks() {
        long old = connect(0);
        state.request(old, 1); state.frame(old, 2);
        state.stop();
        assertFalse(state.decoded(old, 3));
        assertFalse(state.present(old, 4));
        assertFalse(state.failed(old, 5, false));
        long replacement = connect(10);
        assertFalse(state.frame(old, 11));
        assertFalse(state.waiting(old, 11));
        assertFalse(state.opened(old, 11));
        assertTrue(state.request(replacement, 11));
    }
    @Test public void blockedUiDropsOldFrameBeforeRequestingLatest() {
        long id = connect(0);
        state.request(id, 0); state.frame(id, 10); state.decoded(id, 20);
        assertFalse(state.request(id, 2000));
        assertFalse(state.presentationIsFresh(id, 1010));
        assertFalse(state.present(id, 1010));
        assertTrue(state.displayIsStale(1010));
        assertTrue(state.request(id, 1010));
    }
    @Test public void freshnessIncludesDecodeTimeNotJustTimeOnUiQueue() {
        long id = connect(0);
        state.request(id, 0); state.frame(id, 10);
        assertTrue(state.decoded(id, 1005));
        assertTrue(state.presentationIsFresh(id, 1009));
        assertFalse(state.presentationIsFresh(id, 1010));
        assertFalse(state.present(id, 1010));
    }
    @Test public void imageBecomesStaleThreeSecondsAfterLastDisplay() {
        long id = connect(0);
        state.request(id, 0); state.frame(id, 10); state.decoded(id, 20); state.present(id, 30);
        assertFalse(state.displayIsStale(3029));
        assertTrue(state.displayIsStale(3030));
        assertTrue(state.failed(id, 3031, false));
        assertTrue(state.displayIsStale(3031));
    }
    @Test public void bitmapBoundsRejectOversizedOrInvalidDecodedDimensions() {
        assertTrue(ViewerStreamState.safeDimensions(1280, 720));
        assertTrue(ViewerStreamState.safeDimensions(1920, 1080));
        assertFalse(ViewerStreamState.safeDimensions(1920, 1920));
        assertFalse(ViewerStreamState.safeDimensions(0, 720));
        assertFalse(ViewerStreamState.safeDimensions(-1, 720));
        assertFalse(ViewerStreamState.safeDimensions(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
    @Test public void jpegResponseHasAHardByteLimitBeforeDecoding() {
        assertFalse(ViewerStreamState.safePayloadSize(0));
        assertFalse(ViewerStreamState.safePayloadSize(3));
        assertTrue(ViewerStreamState.safePayloadSize(512 * 1024));
        assertFalse(ViewerStreamState.safePayloadSize(512 * 1024 + 1));
    }
}
