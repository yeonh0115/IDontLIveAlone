package com.example.na_honja_ansanda;

import com.example.na_honja_ansanda.data.video.RtcLocalDocument;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class RtcLocalDocumentTest {
    private final RtcLocalDocument document = new RtcLocalDocument("<!doctype html><p>영상</p>");

    @Test public void topLevelGetServesTheExactBundledUtf8DocumentAndCanReload() throws Exception {
        assertTrue(document.allows("GET", true, RtcLocalDocument.PAGE_URL));
        byte[] expected = "<!doctype html><p>영상</p>".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, document.open().readAllBytes());
        assertArrayEquals(expected, document.open().readAllBytes());
    }

    @Test public void scriptsIframesAndNonGetRequestsCannotAcquireBridgeDocument() {
        assertFalse(document.allows("GET", false, RtcLocalDocument.PAGE_URL));
        for (String method : new String[]{"POST", "HEAD", "OPTIONS", "get", "", null}) {
            assertFalse(document.allows(method, true, RtcLocalDocument.PAGE_URL));
        }
    }

    @Test public void DifferentOriginsAndPathVariantsHaveNoNetworkFallback() {
        for (String url : new String[]{
                "https://appassets.androidplatform.net/direct-camera/?next=remote",
                "https://appassets.androidplatform.net/direct-camera/#fragment",
                "https://appassets.androidplatform.net/direct-camera/child",
                "https://appassets.androidplatform.net/direct-camera",
                "https://appassets.androidplatform.net:443/direct-camera/",
                "https://appassets.androidplatform.net.attacker.invalid/direct-camera/",
                "https://attacker.invalid/direct-camera/",
                "http://appassets.androidplatform.net/direct-camera/",
                "file:///android_asset/direct_camera.html", "data:text/html,anything", null}) {
            assertFalse(document.allows("GET", true, url));
        }
    }
}
