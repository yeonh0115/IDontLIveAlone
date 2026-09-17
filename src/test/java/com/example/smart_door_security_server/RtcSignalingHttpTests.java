package com.example.smart_door_security_server;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:rtc-http-tests;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
    "EVENT_PHOTOS_ENABLED=false"
})
class RtcSignalingHttpTests {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired AppSessionService sessions;
    @Autowired AppSessionRepository sessionRows;
    @Autowired PairedDeviceRepository devices;
    @Autowired DeviceRegistrationService registry;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    final JsonMapper json = JsonMapper.builder().build();
    HttpClient client;
    record Identity(User user, String app, String camera, String deviceId) { }
    @BeforeEach void open() { client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(); }
    @AfterEach void close() { client.close(); }

    @Test void signalingRequestsHaveBoundedDatabaseQueryCounts() throws Exception {
        var owner = identity(DeviceRole.CAMERA);
        var idle = identity(DeviceRole.CAMERA);
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            String id = create(owner);
            assertQueries(statistics, "create", 3);
            assertThat(send("GET", "/api/rtc/camera/next", owner.camera(), null).statusCode()).isEqualTo(200);
            assertQueries(statistics, "next-pending", 4);
            assertThat(send("GET", appPath(id), owner.app(), null).statusCode()).isEqualTo(200);
            assertQueries(statistics, "viewer-pending", 4);
            assertThat(send("POST", cameraPath(id) + "/answer", owner.camera(), sdp("answer", "v=0\r\no=query-test")).statusCode()).isEqualTo(204);
            assertQueries(statistics, "answer", 4);
            assertThat(send("GET", cameraPath(id), owner.camera(), null).statusCode()).isEqualTo(200);
            assertQueries(statistics, "camera-ready", 4);
            assertThat(send("GET", appPath(id), owner.app(), null).statusCode()).isEqualTo(200);
            assertQueries(statistics, "viewer-ready", 4);
            assertThat(send("GET", "/api/rtc/camera/next", owner.camera(), null).statusCode()).isEqualTo(204);
            assertQueries(statistics, "next-ready", 2);
            assertThat(send("GET", "/api/rtc/camera/next", idle.camera(), null).statusCode()).isEqualTo(204);
            assertQueries(statistics, "next-idle", 2);
            assertThat(send("POST", appPath(id) + "/close", owner.app(), null).statusCode()).isEqualTo(204);
            assertQueries(statistics, "close", 2);
        } finally { statistics.setStatisticsEnabled(false); }
    }

    private void assertQueries(org.hibernate.stat.Statistics statistics, String operation, long expected) {
        long statements = statistics.getPrepareStatementCount();
        System.out.println("RTC SQL count " + operation + "=" + statements);
        assertThat(statements).as(operation + " SQL statements").isEqualTo(expected);
        statistics.clear();
    }

    @Test void completeHttpExchangeIsPrivateIdempotentAndDoesNotUseVideoRelay() throws Exception {
        var owner = identity(DeviceRole.CAMERA);
        var created = send("POST", "/api/rtc/sessions", owner.app(), sdp("offer", "v=0\r\no=private-offer"));
        assertThat(created.statusCode()).isEqualTo(201); noCache(created);
        JsonNode pending = json.readTree(created.body()); String id = pending.path("sessionId").asText();
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("answerSdp").isNull()).isTrue();
        assertThat(Instant.parse(pending.path("expiresAt").asText())).isBetween(Instant.now().plusSeconds(40), Instant.now().plusSeconds(46));
        var offer = send("GET", "/api/rtc/camera/next", owner.camera(), null); noCache(offer);
        assertThat(offer.statusCode()).isEqualTo(200);
        assertThat(json.readTree(offer.body()).path("sdp").asText()).isEqualTo("v=0\r\no=private-offer");
        assertThat(json.readTree(send("GET", "/api/rtc/camera/next", owner.camera(), null).body()).path("sessionId").asText()).isEqualTo(id);
        String answer = sdp("answer", "v=0\r\no=private-answer");
        assertThat(send("POST", cameraPath(id) + "/answer", owner.camera(), answer).statusCode()).isEqualTo(204);
        assertThat(send("POST", cameraPath(id) + "/answer", owner.camera(), answer).statusCode()).isEqualTo(204);
        assertThat(send("POST", cameraPath(id) + "/answer", owner.camera(), sdp("answer", "changed")).statusCode()).isEqualTo(409);
        assertThat(send("GET", "/api/rtc/camera/next", owner.camera(), null).statusCode()).isEqualTo(204);
        JsonNode ready = json.readTree(send("GET", appPath(id), owner.app(), null).body());
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        assertThat(ready.path("answerType").asText()).isEqualTo("answer");
        assertThat(ready.path("answerSdp").asText()).isEqualTo("v=0\r\no=private-answer");
        assertThat(send("POST", appPath(id) + "/close", owner.app(), null).statusCode()).isEqualTo(204);
        assertThat(send("POST", appPath(id) + "/close", owner.app(), null).statusCode()).isEqualTo(204);
        JsonNode closed = json.readTree(send("GET", cameraPath(id), owner.camera(), null).body());
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED"); assertThat(closed.path("answerSdp").isNull()).isTrue();
        assertThat(VideoStreamingController.latestOwnerFrame(owner.user().getUserNo())).isNull();
    }

    @Test void foreignOwnersOtherAppSessionsAndWrongCredentialTypesCannotReadOrChangeTheSession() throws Exception {
        var owner = identity(DeviceRole.CAMERA); var other = identity(DeviceRole.CAMERA);
        String id = create(owner);
        String secondApp = "Bearer " + sessions.issue(owner.user().getUserNo()).sessionToken();
        for (String caller : List.of(other.app(), secondApp)) {
            assertThat(send("GET", appPath(id), caller, null).statusCode()).isEqualTo(404);
            assertThat(send("POST", appPath(id) + "/close", caller, null).statusCode()).isEqualTo(404);
        }
        assertThat(send("GET", cameraPath(id), other.camera(), null).statusCode()).isEqualTo(404);
        assertThat(send("POST", cameraPath(id) + "/answer", other.camera(), sdp("answer", "not-owner")).statusCode()).isEqualTo(404);
        assertThat(send("GET", appPath(id), owner.camera(), null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/rtc/camera/next", owner.app(), null).statusCode()).isEqualTo(401);
        var unauthorized = send("GET", appPath(id), null, null);
        assertThat(unauthorized.statusCode()).isEqualTo(401); noCache(unauthorized);
        assertThat(send("POST", "/api/rtc/sessions", null, sdp("offer", "anonymous")).statusCode()).isEqualTo(401);
        assertThat(json.readTree(send("GET", appPath(id), owner.app(), null).body()).path("status").asText()).isEqualTo("PENDING");
    }

    @ParameterizedTest @EnumSource(value=DeviceRole.class, names={"SENSOR", "REPORT"})
    void onlyPairedCameraRoleCanParticipate(DeviceRole role) throws Exception {
        var owner = identity(role);
        assertThat(send("GET", "/api/rtc/camera/next", owner.camera(), null).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/rtc/sessions", owner.app(), sdp("offer", "no-camera")).statusCode()).isEqualTo(404);
        var row = devices.findById(owner.deviceId()).orElseThrow(); row.setRole(DeviceRole.CAMERA); row.setUserNo(null); devices.saveAndFlush(row);
        assertThat(send("GET", "/api/rtc/camera/next", owner.camera(), null).statusCode()).isEqualTo(403);
    }

    @ParameterizedTest @ValueSource(strings={"LOGOUT", "APP_EXPIRY", "UNLINK", "REKEY", "DELETE_OWNER"})
    void credentialChangesImmediatelyStopDisclosureOfStoredAnswer(String change) throws Exception {
        var owner = identity(DeviceRole.CAMERA); String id = create(owner);
        assertThat(send("POST", cameraPath(id) + "/answer", owner.camera(), sdp("answer", "private-answer")).statusCode()).isEqualTo(204);
        String camera = owner.camera();
        switch (change) {
            case "LOGOUT" -> sessions.revoke(owner.app());
            case "APP_EXPIRY" -> {
                var row = sessionRows.findById(TokenSecrets.hash(TokenSecrets.bearer(owner.app()))).orElseThrow();
                row.setExpiresAt(Instant.now().minusSeconds(1)); sessionRows.saveAndFlush(row);
            }
            case "UNLINK" -> registry.unlink(owner.app(), owner.deviceId());
            case "REKEY" -> {
                camera = "Bearer " + TokenSecrets.generate();
                var row = devices.findById(owner.deviceId()).orElseThrow(); row.setTokenHash(TokenSecrets.hash(TokenSecrets.bearer(camera))); devices.saveAndFlush(row);
                assertThat(send("GET", cameraPath(id), owner.camera(), null).statusCode()).isEqualTo(401);
            }
            case "DELETE_OWNER" -> users.deleteById(owner.user().getUserNo());
        }
        if (change.equals("DELETE_OWNER")) {
            assertThat(send("GET", appPath(id), owner.app(), null).statusCode()).isEqualTo(401);
            assertThat(send("GET", cameraPath(id), camera, null).statusCode()).isEqualTo(403);
        } else if (change.equals("UNLINK")) {
            assertThat(send("GET", cameraPath(id), camera, null).statusCode()).isEqualTo(403);
            assertClosed(send("GET", appPath(id), owner.app(), null));
        } else {
            assertClosed(send("GET", cameraPath(id), camera, null));
            if (change.equals("REKEY")) assertClosed(send("GET", appPath(id), owner.app(), null));
            else assertThat(send("GET", appPath(id), owner.app(), null).statusCode()).isEqualTo(401);
        }
    }

    @Test void oversizedUtf8AndChunkedJsonAreBoundedAndMalformedJsonHasNoPrivateDiagnostic() throws Exception {
        var owner = identity(DeviceRole.CAMERA);
        assertThat(send("POST", "/api/rtc/sessions", owner.app(), sdp("offer", "가".repeat(22000))).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/rtc/sessions", owner.app(), sdp("answer", "wrong-type")).statusCode()).isEqualTo(400);
        var bad = send("POST", "/api/rtc/sessions", owner.app(), "{private-sdp-content");
        assertThat(bad.statusCode()).isEqualTo(400); assertThat(bad.body()).doesNotContain("private-sdp-content");
        byte[] huge = sdp("offer", "x".repeat(RtcRequestFilter.MAX_JSON_BYTES)).getBytes(StandardCharsets.UTF_8);
        var chunked = HttpRequest.newBuilder(uri("/api/rtc/sessions")).timeout(Duration.ofSeconds(5))
                .header("Authorization", owner.app()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(huge))).build();
        var response = client.send(chunked, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413); noCache(response);
        assertThat(send("GET", "/healthz", null, null).statusCode()).isEqualTo(200);
    }

    private String create(Identity owner) throws Exception {
        var response = send("POST", "/api/rtc/sessions", owner.app(), sdp("offer", "v=0\r\no=private-offer"));
        assertThat(response.statusCode()).isEqualTo(201); return json.readTree(response.body()).path("sessionId").asText();
    }
    private Identity identity(DeviceRole role) {
        var user = new User(); user.setUserId("rtc-" + UUID.randomUUID()); user = users.saveAndFlush(user);
        String app = "Bearer " + sessions.issue(user.getUserNo()).sessionToken(), camera = "Bearer " + TokenSecrets.generate();
        var row = new PairedDevice(); row.setDeviceId(UUID.randomUUID().toString()); row.setUserNo(user.getUserNo()); row.setRole(role);
        row.setTokenHash(TokenSecrets.hash(TokenSecrets.bearer(camera))); row.setName("HTTP test device"); row.setCreatedAt(Instant.now());
        devices.saveAndFlush(row); return new Identity(user, app, camera, row.getDeviceId());
    }
    private HttpResponse<String> send(String method, String path, String authorization, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
        if (authorization != null) builder.header("Authorization", authorization);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private String sdp(String type, String sdp) { return json.writeValueAsString(Map.of("type", type, "sdp", sdp)); }
    private String appPath(String id) { return "/api/rtc/sessions/" + id; }
    private String cameraPath(String id) { return "/api/rtc/camera/sessions/" + id; }
    private void assertClosed(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200); JsonNode state = json.readTree(response.body());
        assertThat(state.path("status").asText()).isEqualTo("CLOSED"); assertThat(state.path("answerSdp").isNull()).isTrue();
    }
    private void noCache(HttpResponse<?> response) { assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store"); }
}
