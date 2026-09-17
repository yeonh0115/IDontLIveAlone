package com.example.na_honja_ansanda.data.video;

import java.nio.charset.StandardCharsets;

/** Native signaling policy. All times use a monotonic clock and all transitions run on main. */
public final class RtcViewerState {
    public enum Phase { STOPPED, LOADING, GATHERING, CREATING, POLLING, CONNECTING, PLAYING, FAILED }
    public static final int MAX_POLLS = 40;
    public static final int MAX_SDP_BYTES = 64 * 1024;
    private long generation, deadline;
    private int polls;
    private Phase phase = Phase.STOPPED;
    public long start(long now) {
        generation++; phase = Phase.LOADING; deadline = now + 10000; polls = 0; return generation;
    }
    public void stop() { generation++; phase = Phase.STOPPED; }
    public boolean current(long id) { return id == generation && phase != Phase.STOPPED && phase != Phase.FAILED; }
    public Phase phase() { return phase; }
    public long generation() { return generation; }
    public boolean pageReady(long id, long now) {
        if (!current(id) || phase != Phase.LOADING) return false;
        phase = Phase.GATHERING; deadline = now + 15000; return true;
    }
    public boolean offer(long id, String sdp, long now) {
        if (!current(id) || phase != Phase.GATHERING || !validSdp(sdp)) return false;
        phase = Phase.CREATING; deadline = now + 185000; return true;
    }
    public boolean created(long id, String sessionId, long now) {
        if (!current(id) || phase != Phase.CREATING || !validSessionId(sessionId)) return false;
        phase = Phase.POLLING; deadline = now + 60000; return true;
    }
    public boolean poll(long id) {
        if (!current(id) || phase != Phase.POLLING || polls >= MAX_POLLS) return false;
        polls++; return true;
    }
    public boolean answer(long id, String type, String sdp, long now) {
        if (!current(id) || phase != Phase.POLLING || !"answer".equals(type) || !validSdp(sdp)) return false;
        phase = Phase.CONNECTING; deadline = now + 20000; return true;
    }
    public boolean playing(long id) {
        if (!current(id) || (phase != Phase.CONNECTING && phase != Phase.PLAYING)) return false;
        phase = Phase.PLAYING; return true;
    }
    public boolean canLease(long id) {
        return current(id) && (phase == Phase.CONNECTING || phase == Phase.PLAYING);
    }
    public boolean expired(long now) {
        return phase != Phase.STOPPED && phase != Phase.FAILED && phase != Phase.PLAYING && now >= deadline;
    }
    public boolean fail(long id) {
        if (!current(id)) return false;
        generation++; phase = Phase.FAILED; return true;
    }
    public static boolean validSessionId(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
    public static boolean validSdp(String value) {
        return value != null && value.startsWith("v=0") && value.length() <= MAX_SDP_BYTES
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_SDP_BYTES;
    }
    public static boolean directCandidate(String type) {
        return "host".equals(type) || "srflx".equals(type) || "prflx".equals(type);
    }
}
