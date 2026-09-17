package com.example.na_honja_ansanda.data.video;

import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** Only fixed labels may cross the diagnostic bridge. Never log untrusted strings. */
public final class RtcDiagnostics {
    private RtcDiagnostics() { }
    public enum Stage {
        START, PAGE_CREATED, PAGE_LOAD, LOCAL_DOCUMENT_SERVED, PAGE_STARTED, PAGE_ORIGIN_REJECTED, PAGE_FINISHED,
        PAGE_READY, JS_START_SENT, OFFER_RECEIVED, OFFER_INVALID, OFFER_JSON_FAILED,
        CREATE_HTTP_START, STATUS_HTTP_START, CREATE_HTTP_RESPONSE, STATUS_HTTP_RESPONSE,
        CREATE_NETWORK_FAILED, STATUS_NETWORK_FAILED, HTTP_BODY_INVALID,
        SESSION_CREATED, SESSION_ID_INVALID, SESSION_ID_MISMATCH, ANSWER_INVALID,
        ANSWER_SENT, PLAYING, POLL_LIMIT, TIMEOUT, FAILED, STOP, SESSION_CHANGED,
        WEBVIEW_ERROR, WEBVIEW_CONSOLE_ERROR, WEBVIEW_RESOURCE_BLOCKED, WEBVIEW_NAVIGATION_BLOCKED,
        WEBVIEW_PERMISSION_DENIED, WEBVIEW_RENDERER_GONE, PAGE_SETUP_FAILED, CANDIDATE_REJECTED
    }
    private static final Set<String> JS_EVENTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "page_boot", "page_secure", "page_insecure", "start", "unsupported_rtc", "peer_create", "peer_create_failed",
            "transceiver_add", "transceiver_failed", "offer_create", "offer_create_failed",
            "offer_created", "local_set", "local_set_failed", "local_set_done", "ice_gathering",
            "ice_gather_timeout", "ice_gather_complete", "offer_submit", "offer_submit_failed",
            "answer_received", "remote_set_failed", "remote_set_done", "track_received",
            "video_play_failed", "video_playing", "ice_new", "ice_checking", "ice_connected",
            "ice_completed", "ice_disconnected", "ice_failed", "ice_closed", "connection_new",
            "connection_connecting", "connection_connected", "connection_disconnected",
            "connection_failed", "connection_closed", "disconnected_timeout", "relay_rejected",
            "stalled", "stats_failed", "page_error", "promise_rejected", "closed")));
    public static boolean allowedJsEvent(String value) { return value != null && JS_EVENTS.contains(value); }
    public static boolean boundedIceCounts(int host, int srflx, int relay) {
        return host >= 0 && srflx >= 0 && relay >= 0 && (long) host + srflx + relay <= 256;
    }
    public static String serverState(String value) {
        if ("PENDING".equals(value) || "READY".equals(value) || "CLOSED".equals(value) || "EXPIRED".equals(value)) return value;
        return "INVALID";
    }
}
