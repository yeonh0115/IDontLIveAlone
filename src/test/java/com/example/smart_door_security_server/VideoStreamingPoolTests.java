package com.example.smart_door_security_server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.zaxxer.hikari.HikariDataSource;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:stream-pool-test;MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0",
    "spring.datasource.hikari.connection-timeout=1000", "spring.lifecycle.timeout-per-shutdown-phase=2s",
    "app.video-idle-timeout-ms=1000"
})
class VideoStreamingPoolTests {
    @TempDir static Path storage;
    @DynamicPropertySource static void productionStreamingSettings(DynamicPropertyRegistry registry) throws IOException {
        Properties production=new Properties();
        try(var input=Files.newInputStream(Path.of("src/main/resources/application.properties"))) { production.load(input); }
        // Exercise the actual production setting; its framework default used to differ from tests.
        registry.add("spring.jpa.open-in-view", () -> production.getProperty("spring.jpa.open-in-view","true"));
        registry.add("app.storage-dir", () -> storage.toString());
    }
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired AppSessionService sessions;
    @Autowired HikariDataSource dataSource;

    @Test void severalAuthenticatedVideoFeedsDoNotExhaustSmallPoolAndHealthStaysAvailable() throws Exception {
        User user=new User(); user.setUserId("stream-"+UUID.randomUUID()); user=users.saveAndFlush(user);
        Integer owner=user.getUserNo(); String app="Bearer "+sessions.issue(owner).sessionToken();
        List<InputStream> streams=new ArrayList<>();
        var producer=Executors.newSingleThreadScheduledExecutor();
        producer.scheduleAtFixedRate(() -> VideoStreamingController.updateOwnerFrame(owner,new byte[]{1,2,3}),0,30,TimeUnit.MILLISECONDS);
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
          try {
            for(int i=0;i<4;i++) {
                var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/video_feed"))
                        .header("Authorization",app).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                assertThat(response.statusCode()).isEqualTo(200); streams.add(response.body());
                assertThat(response.body().readNBytes(7)).isNotEmpty();
                assertThat(dataSource.getHikariPoolMXBean().getActiveConnections())
                        .as("An ongoing MJPEG response must release its authentication DB connection").isZero();
            }
            var health=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/healthz"))
                    .timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isLessThan(2);
          } finally {
            for(var stream:streams) stream.close();
            producer.shutdownNow(); VideoStreamingController.clearOwnerFrame(owner);
          }
        }
    }

    @Test void cameraOfflineEndsIdleFeedInsteadOfRetainingAnHttpWorkerIndefinitely() throws Exception {
        User user=new User(); user.setUserId("idle-"+UUID.randomUUID()); user=users.saveAndFlush(user);
        String app="Bearer "+sessions.issue(user.getUserNo()).sessionToken();
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/video_feed"))
                    .header("Authorization",app).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).isEmpty();
            assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }
}
