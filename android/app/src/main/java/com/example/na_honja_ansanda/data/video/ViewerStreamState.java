package com.example.na_honja_ansanda.data.video;

/** Monotonic-clock policy; callbacks from a replaced connection cannot advance the stream. */
public final class ViewerStreamState {
    public enum Phase { STOPPED, CONNECTING, READY, AWAITING, DECODING, UI_PENDING, RETRY_WAIT, AUTH_REQUIRED }
    public static final long RESPONSE_TIMEOUT_MS = 2000;
    public static final long FRAME_INTERVAL_MS = 100;
    public static final long UI_MAX_WAIT_MS = 1000;
    public static final long STALE_DISPLAY_MS = 3000;
    public static final int MAX_JPEG_BYTES = 512 * 1024;
    private Phase phase = Phase.STOPPED;
    private long generation, deadline, nextRequestAt, lastRequestAt, frameReceivedAt;
    private long lastDisplayAt = -1;

    public synchronized long start(long now) {
        generation++;
        phase = Phase.CONNECTING;
        deadline = now + RESPONSE_TIMEOUT_MS;
        lastDisplayAt = -1;
        return generation;
    }
    public synchronized void stop() { generation++; phase = Phase.STOPPED; lastDisplayAt = -1; }
    public synchronized long generation() { return generation; }
    public synchronized Phase phase() { return phase; }
    public synchronized boolean isCurrent(long id) {
        return id == generation && phase != Phase.STOPPED && phase != Phase.AUTH_REQUIRED && phase != Phase.RETRY_WAIT;
    }
    public synchronized boolean opened(long id, long now) {
        if (id != generation || phase != Phase.CONNECTING) return false;
        phase = Phase.READY; nextRequestAt = now; return true;
    }
    public synchronized boolean request(long id, long now) {
        if (id != generation || phase != Phase.READY || now < nextRequestAt) return false;
        phase = Phase.AWAITING; lastRequestAt = now; deadline = now + RESPONSE_TIMEOUT_MS; return true;
    }
    public synchronized boolean waiting(long id, long now) {
        if (id != generation || phase != Phase.AWAITING || now >= deadline) return false;
        phase = Phase.READY; nextRequestAt = now + FRAME_INTERVAL_MS; return true;
    }
    public synchronized boolean frame(long id, long now) {
        if (id != generation || phase != Phase.AWAITING || now >= deadline) return false;
        phase = Phase.DECODING; frameReceivedAt = now; return true;
    }
    public synchronized boolean decoded(long id, long now) {
        if (id != generation || phase != Phase.DECODING) return false;
        phase = Phase.UI_PENDING; return true;
    }
    public synchronized boolean presentationIsFresh(long id, long now) {
        return id == generation && phase == Phase.UI_PENDING && now - frameReceivedAt < UI_MAX_WAIT_MS;
    }
    public synchronized boolean present(long id, long now) {
        if (id != generation || phase != Phase.UI_PENDING) return false;
        boolean fresh = presentationIsFresh(id, now);
        phase = Phase.READY; nextRequestAt = Math.max(now, lastRequestAt + FRAME_INTERVAL_MS);
        if (fresh) lastDisplayAt = now;
        return fresh;
    }
    public synchronized boolean failed(long id, long now, boolean authentication) {
        if (!isCurrent(id)) return false;
        generation++; phase = authentication ? Phase.AUTH_REQUIRED : Phase.RETRY_WAIT;
        nextRequestAt = now + 1000;
        if (authentication) lastDisplayAt = -1;
        return true;
    }
    public synchronized boolean timedOut(long now) {
        return (phase == Phase.AWAITING || phase == Phase.CONNECTING) && now >= deadline;
    }
    public synchronized boolean retryDue(long now) { return phase == Phase.RETRY_WAIT && now >= nextRequestAt; }
    public synchronized long reconnect(long now) {
        if (!retryDue(now)) return -1;
        generation++; phase = Phase.CONNECTING; deadline = now + RESPONSE_TIMEOUT_MS; return generation;
    }
    public synchronized boolean displayIsStale(long now) {
        return lastDisplayAt < 0 || now - lastDisplayAt >= STALE_DISPLAY_MS;
    }
    public static boolean safeDimensions(int width, int height) {
        return width >= 16 && height >= 16 && width <= 1920 && height <= 1920
                && (long) width * height <= 1920L * 1080L;
    }
    public static boolean safePayloadSize(int bytes) { return bytes >= 4 && bytes <= MAX_JPEG_BYTES; }
}
