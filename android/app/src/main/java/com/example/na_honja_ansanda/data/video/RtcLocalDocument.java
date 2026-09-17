package com.example.na_honja_ansanda.data.video;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A single bundled document, with no network fallback or remotely selected asset path. */
public final class RtcLocalDocument {
    public static final String PAGE_URL = "https://appassets.androidplatform.net/direct-camera/";
    private final byte[] html;

    public RtcLocalDocument(String html) { this.html = html.getBytes(StandardCharsets.UTF_8); }

    public boolean allows(String method, boolean mainFrame, String url) {
        return mainFrame && "GET".equals(method) && PAGE_URL.equals(url);
    }

    /** A new stream is required for every WebView request, including a reload. */
    public InputStream open() { return new ByteArrayInputStream(html); }
}
