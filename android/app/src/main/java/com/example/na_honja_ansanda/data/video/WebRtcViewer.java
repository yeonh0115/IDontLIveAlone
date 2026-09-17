package com.example.na_honja_ansanda.data.video;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Only bundled HTML sees this bridge. Credentials and fixed HTTPS API paths stay native. */
public final class WebRtcViewer implements AutoCloseable {
    public enum Status { CONNECTING, PREPARING, PLAYING, LOGIN_REQUIRED, NO_CAMERA, DIRECT_UNAVAILABLE }
    public interface Credentials { String authorization(); long revision(); }
    public interface Listener { void onStatus(Status status); }
    private static final String ORIGIN = "https://idontlivealone.onrender.com";
    private static final String SESSIONS = "/api/rtc/sessions";
    private static final String PAGE_URL = "https://appassets.androidplatform.net/direct-camera/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();
    private static final OkHttpClient CLOSE_HTTP = HTTP.newBuilder().callTimeout(5, TimeUnit.SECONDS).build();
    private static final OkHttpClient CREATE_HTTP = HTTP.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RtcViewerState state = new RtcViewerState();
    private final FrameLayout container;
    private final Credentials credentials;
    private final Listener listener;
    private boolean active, closed;
    private long sessionRevision;
    private String authorization, sessionId;
    private WebView webView;
    private Call activeCall;

    public WebRtcViewer(FrameLayout container, Credentials credentials, Listener listener) {
        this.container = container; this.credentials = credentials; this.listener = listener;
    }
    /** Lifecycle methods are main-thread only. A stopped/failed viewer never retries by itself. */
    public void start() {
        if (closed || active) return;
        active = true;
        sessionRevision = credentials.revision();
        authorization = credentials.authorization();
        long id = state.start(now());
        listener.onStatus(Status.CONNECTING);
        if (authorization == null || authorization.isEmpty()) { terminate(id, Status.LOGIN_REQUIRED); return; }
        if (credentials.revision() != sessionRevision) { stop(); start(); return; }
        try { createPage(id); }
        catch (IOException | RuntimeException error) { terminate(id, Status.DIRECT_UNAVAILABLE); return; }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!active || state.generation() != id) return;
                if (credentials.revision() != sessionRevision) { stop(); start(); return; }
                if (state.expired(now())) { terminate(id, Status.DIRECT_UNAVAILABLE); return; }
                main.postDelayed(this, 500);
            }
        }, 500);
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void createPage(long id) throws IOException {
        String html;
        try (InputStream asset = container.getContext().getAssets().open("direct_camera.html")) {
            html = readLimited(asset, 64 * 1024);
        }
        WebView page = new WebView(container.getContext());
        webView = page;
        WebSettings settings = page.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        page.setBackgroundColor(android.graphics.Color.BLACK);
        page.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        page.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // The document, script and style are bundled inline. No HTTP subresource or iframe is allowed.
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                        Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (valid(id) && !PAGE_URL.equals(url)) terminate(id, Status.DIRECT_UNAVAILABLE);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) terminate(id, Status.DIRECT_UNAVAILABLE);
            }
        });
        page.addJavascriptInterface(new Bridge(id), "NativeCamera");
        container.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.loadDataWithBaseURL(PAGE_URL, html, "text/html", "UTF-8", null);
    }
    /** No URL, token, arbitrary action or JavaScript-evaluation method is exposed. */
    public final class Bridge {
        private final long id;
        Bridge(long id) { this.id = id; }
        @JavascriptInterface public void ready() {
            main.post(() -> {
                if (valid(id) && state.pageReady(id, now())) evaluate("window.DirectCamera.start();");
            });
        }
        @JavascriptInterface public void offer(String sdp) {
            if (!RtcViewerState.validSdp(sdp)) { main.post(() -> terminate(id, Status.DIRECT_UNAVAILABLE)); return; }
            main.post(() -> {
                if (!valid(id) || !state.offer(id, sdp, now())) return;
                listener.onStatus(Status.PREPARING);
                try {
                    JSONObject body = new JSONObject().put("type", "offer").put("sdp", sdp);
                    send(id, true, new Request.Builder().url(ORIGIN + SESSIONS)
                            .header("Authorization", authorization)
                            .post(RequestBody.create(body.toString(), JSON)).build());
                } catch (JSONException error) { terminate(id, Status.DIRECT_UNAVAILABLE); }
            });
        }
        @JavascriptInterface public void state(String event) {
            if (!"playing".equals(event) && !"failed".equals(event)) return;
            main.post(() -> {
                if (!valid(id)) return;
                if ("failed".equals(event)) terminate(id, Status.DIRECT_UNAVAILABLE);
                else if (state.playing(id)) listener.onStatus(Status.PLAYING);
            });
        }
        @JavascriptInterface public void stats(String localType, String remoteType, long bytes, long frames) {
            if (bytes < 0 || frames < 0) return;
            boolean direct = RtcViewerState.directCandidate(localType) && RtcViewerState.directCandidate(remoteType);
            main.post(() -> {
                if (!valid(id) || !state.canLease(id)) return;
                if (!direct) { terminate(id, Status.DIRECT_UNAVAILABLE); return; }
                // Deliberately excludes SDP, IP addresses, tokens and session/device IDs.
                Log.d("DIRECT_VIDEO", "ICE=" + localType + "/" + remoteType
                        + " receivedBytes=" + bytes + " decodedFrames=" + frames);
            });
        }
    }
    private void poll(long id) {
        if (!valid(id) || activeCall != null) return;
        if (!state.poll(id)) { terminate(id, Status.DIRECT_UNAVAILABLE); return; }
        getStatus(id);
    }
    private void lease(long id) {
        if (!valid(id) || !state.canLease(id) || activeCall != null) return;
        getStatus(id);
    }
    private void getStatus(long id) {
        if (!RtcViewerState.validSessionId(sessionId)) { terminate(id, Status.DIRECT_UNAVAILABLE); return; }
        send(id, false, new Request.Builder().url(ORIGIN + SESSIONS + "/" + sessionId)
                .header("Authorization", authorization).get().build());
    }
    private void send(long id, boolean creating, Request request) {
        if (!valid(id) || activeCall != null) return;
        String requestAuthorization = authorization;
        Call call = (creating ? CREATE_HTTP : HTTP).newCall(request); activeCall = call;
        call.enqueue(new Callback() {
            @Override public void onFailure(Call failed, IOException error) {
                main.post(() -> {
                    if (valid(id) && activeCall == failed) { activeCall = null; terminate(id, Status.DIRECT_UNAVAILABLE); }
                });
            }
            @Override public void onResponse(Call completed, Response response) {
                int code = response.code();
                JSONObject body = null;
                try (Response closeable = response) {
                    ResponseBody content = closeable.body();
                    if (code >= 200 && code < 300 && content != null) {
                        body = new JSONObject(readLimited(content.byteStream(), 512 * 1024));
                    }
                } catch (IOException | JSONException ignored) { code = 0; }
                final int resultCode = code;
                final JSONObject result = body;
                main.post(() -> {
                    if (!valid(id) || activeCall != completed) {
                        // If cancellation raced a completed create, close only that old session.
                        if (creating && result != null) closeSession(result.optString("sessionId"), requestAuthorization);
                        return;
                    }
                    activeCall = null;
                    handleResponse(id, creating, resultCode, result);
                });
            }
        });
    }
    private void handleResponse(long id, boolean creating, int code, JSONObject body) {
        if (code == 401) { terminate(id, Status.LOGIN_REQUIRED); return; }
        if (creating && code == 404) { terminate(id, Status.NO_CAMERA); return; }
        if (code != (creating ? 201 : 200) || body == null) { terminate(id, Status.DIRECT_UNAVAILABLE); return; }
        String returnedId = body.optString("sessionId");
        if (creating) {
            if (!state.created(id, returnedId, now())) { terminate(id, Status.DIRECT_UNAVAILABLE); return; }
            sessionId = returnedId;
        } else if (!sessionId.equals(returnedId)) { terminate(id, Status.DIRECT_UNAVAILABLE); return; }
        String status = body.optString("status");
        if ("READY".equals(status)) {
            if (state.phase() == RtcViewerState.Phase.POLLING) {
                String answer = body.optString("answerSdp");
                if (!state.answer(id, body.optString("answerType"), answer, now())) {
                    terminate(id, Status.DIRECT_UNAVAILABLE); return;
                }
                evaluate("window.DirectCamera.acceptAnswer(" + quote(answer) + ");");
            }
            main.postDelayed(() -> lease(id), 10000);
        } else if ("PENDING".equals(status) && state.phase() == RtcViewerState.Phase.POLLING) {
            main.postDelayed(() -> poll(id), 750);
        } else terminate(id, Status.DIRECT_UNAVAILABLE);
    }
    private void evaluate(String script) { if (webView != null) webView.evaluateJavascript(script, null); }
    private boolean valid(long id) {
        return active && state.current(id) && sessionRevision == credentials.revision();
    }
    private void terminate(long id, Status status) {
        if (!valid(id) || !state.fail(id)) return;
        cleanup(); listener.onStatus(status);
    }
    public void stop() {
        state.stop(); cleanup(); listener.onStatus(Status.CONNECTING);
    }
    private void cleanup() {
        active = false;
        main.removeCallbacksAndMessages(null);
        if (activeCall != null) { activeCall.cancel(); activeCall = null; }
        String oldSession = sessionId, oldAuthorization = authorization;
        sessionId = null; authorization = null;
        if (webView != null) {
            WebView oldPage = webView; webView = null;
            oldPage.evaluateJavascript("if(window.DirectCamera){window.DirectCamera.close();}", null);
            oldPage.removeJavascriptInterface("NativeCamera");
            oldPage.stopLoading(); oldPage.onPause();
            container.removeView(oldPage); oldPage.destroy();
        }
        closeSession(oldSession, oldAuthorization);
    }
    private static void closeSession(String id, String auth) {
        if (!RtcViewerState.validSessionId(id) || auth == null || auth.isEmpty()) return;
        Request request = new Request.Builder().url(ORIGIN + SESSIONS + "/" + id + "/close")
                .header("Authorization", auth).post(RequestBody.create(new byte[0], null)).build();
        CLOSE_HTTP.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { /* Lease expiry bounds cleanup. */ }
            @Override public void onResponse(Call call, Response response) { response.close(); }
        });
    }
    private static String readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Response exceeds limit");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
    private static String quote(String value) {
        return JSONObject.quote(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
    @Override public void close() { stop(); closed = true; }
    private static long now() { return SystemClock.elapsedRealtime(); }
}
