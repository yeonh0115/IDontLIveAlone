package com.example.smart_door_security_server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegacyPhotoUrlTests {
    @TempDir static Path storage;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage-dir", () -> storage.toString());
    }

    @Test
    void oldUploadUrlsStillServeFilesFromPicturesWithoutShadowingUploads() throws Exception {
        byte[] legacyBytes = new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
        byte[] uploadedBytes = new byte[]{(byte) 0xff, (byte) 0xd8, 3, 4, (byte) 0xff, (byte) 0xd9};
        Files.createDirectories(storage.resolve("pictures"));
        Files.createDirectories(storage.resolve("uploads"));
        Files.write(storage.resolve("pictures/legacy-event.jpg"), legacyBytes);
        Files.write(storage.resolve("pictures/overlap.jpg"), legacyBytes);
        Files.write(storage.resolve("uploads/overlap.jpg"), uploadedBytes);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> legacy = fetch(client, "/uploads/legacy-event.jpg");
            assertEquals(200, legacy.statusCode());
            assertArrayEquals(legacyBytes, legacy.body());
            HttpResponse<byte[]> uploaded = fetch(client, "/uploads/overlap.jpg");
            assertEquals(200, uploaded.statusCode());
            assertArrayEquals(uploadedBytes, uploaded.body());
            assertEquals(404, fetch(client, "/uploads/missing-event.jpg").statusCode());
        }
    }

    private HttpResponse<byte[]> fetch(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}
