package com.example.smart_door_security_server;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bound even chunked JSON before deserialization, and never cache private signaling. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RtcRequestFilter extends OncePerRequestFilter {
    static final int MAX_JSON_BYTES = 512 * 1024;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/rtc/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        if (!"POST".equals(request.getMethod())) { chain.doFilter(request, response); return; }
        if (request.getContentLengthLong() > MAX_JSON_BYTES) { tooLarge(response); return; }
        byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
        if (body.length > MAX_JSON_BYTES) { tooLarge(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous JSON endpoint"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }
    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413); response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"request_too_large\"}");
    }
}
