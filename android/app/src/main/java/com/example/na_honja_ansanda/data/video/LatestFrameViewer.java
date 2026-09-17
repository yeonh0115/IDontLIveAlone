package com.example.na_honja_ansanda.data.video;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** One request, one decoder and one coalesced UI delivery; no frame history is retained. */
public final class LatestFrameViewer implements AutoCloseable {
    public enum Status { WAITING, LOGIN_REQUIRED }
    public interface Credentials { String authorization(); long revision(); }
    public interface Listener { void onFrame(Bitmap bitmap); void onStatus(Status status); }
    private static final String URL = "wss://idontlivealone.onrender.com/ws/viewer";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    private final ViewerStreamState state = new ViewerStreamState();
    private final Credentials credentials;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding = new AtomicBoolean();
    private final AtomicBoolean uiQueued = new AtomicBoolean();
    private final AtomicReference<PendingFrame> pending = new AtomicReference<>();
    private final Object socketLock = new Object();
    private volatile boolean active, closed;
    private volatile WebSocket socket;
    private volatile long sessionRevision;
    private volatile Status lastStatus;
    private ScheduledFuture<?> ticker;
    private final Runnable uiDelivery = this::deliver;

    private static final class PendingFrame {
        final long generation;
        final Bitmap bitmap;
        PendingFrame(long generation, Bitmap bitmap) { this.generation = generation; this.bitmap = bitmap; }
    }
    public LatestFrameViewer(Credentials credentials, Listener listener) {
        this.credentials = credentials; this.listener = listener;
    }
    /** Called on the main thread. */
    public void start() {
        if (closed || active) return;
        active = true;
        lastStatus = null;
        listener.onStatus(Status.WAITING);
        control.execute(this::beginSession);
        ticker = control.scheduleWithFixedDelay(this::tick, 25, 25, TimeUnit.MILLISECONDS);
    }
    private void beginSession() {
        if (!active) return;
        cancelSocket(); discardPending();
        sessionRevision = credentials.revision();
        String authorization = credentials.authorization();
        long id = state.start(now());
        if (authorization == null || authorization.isEmpty()) {
            state.failed(id, now(), true); queueUi(); return;
        }
        if (credentials.revision() != sessionRevision) return;
        connect(id, authorization);
    }
    private void connect(long id, String authorization) {
        if (!active || !state.isCurrent(id)) return;
        synchronized (socketLock) {
        if (!valid(id)) return;
        socket = CLIENT.newWebSocket(new Request.Builder().url(URL)
                .header("Authorization", authorization).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                if (!valid(id) || !state.opened(id, now())) { ws.cancel(); return; }
                executeControl(() -> requestNext(id));
            }
            @Override public void onMessage(WebSocket ws, String text) {
                if (!valid(id)) return;
                if (!"waiting".equals(text)) { fail(id, false); return; }
                state.waiting(id, now());
            }
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                if (!valid(id)) return;
                if (!ViewerStreamState.safePayloadSize(bytes.size())
                        || bytes.getByte(0) != (byte) 0xff || bytes.getByte(1) != (byte) 0xd8) {
                    fail(id, false); return;
                }
                // A cancelled connection may still have its single decode running. Drop this
                // response instead of adding another job to the executor's queue.
                if (!decoding.compareAndSet(false, true)) { state.waiting(id, now()); return; }
                if (!state.frame(id, now())) { decoding.set(false); return; }
                try { decoder.execute(() -> decode(id, bytes)); }
                catch (java.util.concurrent.RejectedExecutionException ignored) { decoding.set(false); }
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, null); fail(id, code == 1008);
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) { fail(id, code == 1008); }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                fail(id, response != null && (response.code() == 401 || response.code() == 403));
            }
        });
        }
    }
    private void decode(long id, ByteString bytes) {
        Bitmap bitmap = null;
        try {
            if (!valid(id)) return;
            byte[] jpeg = bytes.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            if (!"image/jpeg".equals(bounds.outMimeType)
                    || !ViewerStreamState.safeDimensions(bounds.outWidth, bounds.outHeight)) {
                fail(id, false); return;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            if (bitmap == null) { fail(id, false); return; }
            if (!valid(id) || !state.decoded(id, now())) return;
            PendingFrame candidate = new PendingFrame(id, bitmap);
            PendingFrame previous = pending.getAndSet(candidate);
            bitmap = null;
            if (previous != null) previous.bitmap.recycle();
            // stop()/session replacement can happen between decoded() and publication.
            if (!valid(id)) {
                if (pending.compareAndSet(candidate, null)) candidate.bitmap.recycle();
                return;
            }
            queueUi();
        } catch (RuntimeException | OutOfMemoryError error) {
            fail(id, false);
        } finally {
            if (bitmap != null) bitmap.recycle();
            decoding.set(false);
        }
    }
    private void tick() {
        if (!active) return;
        if (credentials.revision() != sessionRevision) { beginSession(); return; }
        long time = now();
        if (state.timedOut(time)) fail(state.generation(), false);
        if (state.retryDue(time)) {
            String authorization = credentials.authorization();
            long id = state.reconnect(time);
            if (authorization == null) { state.failed(id, time, true); queueUi(); }
            else connect(id, authorization);
        }
        requestNext(state.generation());
        Status status = state.phase() == ViewerStreamState.Phase.AUTH_REQUIRED
                ? Status.LOGIN_REQUIRED : Status.WAITING;
        if (state.displayIsStale(time) && lastStatus != status) queueUi();
    }
    private void requestNext(long id) {
        WebSocket current = socket;
        if (current != null && valid(id) && state.request(id, now()) && !current.send("next")) fail(id, false);
    }
    private boolean valid(long id) {
        return active && credentials.revision() == sessionRevision && state.isCurrent(id);
    }
    private void fail(long id, boolean authentication) {
        synchronized (socketLock) {
            if (!active || !state.failed(id, now(), authentication)) return;
            WebSocket failed = socket; socket = null;
            // Cancel this connection now; a queued cleanup must never cancel its replacement.
            if (failed != null) failed.cancel();
        }
        queueUi();
    }
    private void queueUi() {
        if (active && uiQueued.compareAndSet(false, true)) main.post(uiDelivery);
    }
    private void deliver() {
        uiQueued.set(false);
        PendingFrame frame = pending.getAndSet(null);
        boolean displayed = false;
        if (frame != null) {
            if (valid(frame.generation) && state.presentationIsFresh(frame.generation, now())) {
                listener.onFrame(frame.bitmap); lastStatus = null; displayed = true;
                state.present(frame.generation, now());
            } else {
                frame.bitmap.recycle();
                state.present(frame.generation, now());
            }
        }
        if (!active) return;
        if (!displayed && (credentials.revision() != sessionRevision || state.displayIsStale(now()))) {
            Status status = state.phase() == ViewerStreamState.Phase.AUTH_REQUIRED
                    ? Status.LOGIN_REQUIRED : Status.WAITING;
            if (lastStatus != status) { listener.onStatus(status); lastStatus = status; }
        }
        executeControl(() -> requestNext(state.generation()));
    }
    /** Called on the main thread, also invalidates all previously posted callbacks. */
    public void stop() {
        active = false; state.stop();
        if (ticker != null) { ticker.cancel(false); ticker = null; }
        cancelSocket(); discardPending();
        main.removeCallbacks(uiDelivery); uiQueued.set(false);
        listener.onStatus(Status.WAITING);
    }
    private void cancelSocket() {
        synchronized (socketLock) {
        WebSocket previous = socket; socket = null;
        if (previous != null) previous.cancel();
        }
    }
    private void discardPending() {
        PendingFrame previous = pending.getAndSet(null);
        if (previous != null) previous.bitmap.recycle();
    }
    private void executeControl(Runnable action) {
        if (closed) return;
        try { control.execute(action); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    @Override public void close() {
        stop(); closed = true; control.shutdownNow(); decoder.shutdownNow();
    }
    private static long now() { return SystemClock.elapsedRealtime(); }
}
