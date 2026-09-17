package com.example.na_honja_ansanda;

import com.example.na_honja_ansanda.data.video.RtcDiagnostics;
import org.junit.Test;
import static org.junit.Assert.*;

public class RtcDiagnosticsTest {
    @Test public void diagnosticBridgeAcceptsOnlyFixedStageNames() {
        assertTrue(RtcDiagnostics.allowedJsEvent("page_boot"));
        assertTrue(RtcDiagnostics.allowedJsEvent("ice_gather_timeout"));
        assertTrue(RtcDiagnostics.allowedJsEvent("remote_set_failed"));
        assertFalse(RtcDiagnostics.allowedJsEvent("Bearer private-value"));
        assertFalse(RtcDiagnostics.allowedJsEvent("https://example.com/private"));
        assertFalse(RtcDiagnostics.allowedJsEvent("v=0\r\na=candidate:private"));
        assertFalse(RtcDiagnostics.allowedJsEvent("ice_failed\nprivate-value"));
        assertFalse(RtcDiagnostics.allowedJsEvent(null));
    }
    @Test public void unknownServerStatusCannotLeakIntoLogs() {
        assertEquals("PENDING", RtcDiagnostics.serverState("PENDING"));
        assertEquals("READY", RtcDiagnostics.serverState("READY"));
        assertEquals("CLOSED", RtcDiagnostics.serverState("CLOSED"));
        assertEquals("EXPIRED", RtcDiagnostics.serverState("EXPIRED"));
        assertEquals("INVALID", RtcDiagnostics.serverState("private server details"));
        assertEquals("INVALID", RtcDiagnostics.serverState(null));
    }
    @Test public void candidateDiagnosticsRejectInvalidOrUnboundedCounts() {
        assertTrue(RtcDiagnostics.boundedIceCounts(2, 1, 0));
        assertTrue(RtcDiagnostics.boundedIceCounts(0, 0, 0));
        assertFalse(RtcDiagnostics.boundedIceCounts(-1, 0, 0));
        assertFalse(RtcDiagnostics.boundedIceCounts(256, 1, 0));
        assertFalse(RtcDiagnostics.boundedIceCounts(Integer.MAX_VALUE, Integer.MAX_VALUE, 0));
    }
}
