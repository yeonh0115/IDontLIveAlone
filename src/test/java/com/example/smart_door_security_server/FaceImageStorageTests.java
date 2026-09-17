package com.example.smart_door_security_server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class FaceImageStorageTests {
    @TempDir Path root;

    @Test void twentyMegapixelJpegIsSubsampledBeforeRgbConversionAndTemporaryImagesCanBeRemoved() throws Exception {
        FaceImageStorage storage = new FaceImageStorage(root.toString());
        byte[] bytes = image(4000, 5000, false);
        var file = new MockMultipartFile("face", "face.jpg", "image/jpeg", bytes);
        String id = UUID.randomUUID().toString();
        Path saved = storage.save(id, List.of(file, file, file));
        for (int i = 1; i <= 3; i++) {
            BufferedImage decoded = ImageIO.read(saved.resolve("face" + i + ".jpg").toFile());
            assertThat(decoded.getWidth()).isEqualTo(1000);
            assertThat(decoded.getHeight()).isEqualTo(1250);
            decoded.flush();
        }
        assertThat(storage.deleteTask(id)).isTrue();
        assertThat(storage.hasImages(id)).isFalse();
        assertThat(storage.deleteTask(id)).isTrue();
    }

    @Test void subsampledTransparentPngIsCompositedOnWhiteWithoutChangingAspectRatio() throws Exception {
        FaceImageStorage storage = new FaceImageStorage(root.toString());
        var file = new MockMultipartFile("face", "face.png", "image/png", image(2560, 1600, true));
        Path saved = storage.save(UUID.randomUUID().toString(), List.of(file, file, file));
        BufferedImage decoded = ImageIO.read(saved.resolve("face1.jpg").toFile());
        assertThat(decoded.getWidth()).isEqualTo(1280);
        assertThat(decoded.getHeight()).isEqualTo(800);
        Color transparent = new Color(decoded.getRGB(10, 10));
        assertThat(transparent.getRed()).isGreaterThan(245);
        assertThat(transparent.getGreen()).isGreaterThan(245);
        assertThat(transparent.getBlue()).isGreaterThan(245);
        Color opaque = new Color(decoded.getRGB(1000, 400));
        assertThat(opaque.getRed()).isGreaterThan(240);
        assertThat(opaque.getBlue()).isLessThan(15);
        decoded.flush();
    }

    @Test void concurrentConversionIsRejectedWithoutWritingAndPermitIsReleasedAfterFailure() throws Exception {
        FaceImageStorage storage = new FaceImageStorage(root.toString());
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blocking = new MockMultipartFile("face", "bad.jpg", "image/jpeg", new byte[]{1}) {
            @Override public InputStream getInputStream() throws IOException {
                started.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                throw new IOException("Test decode failure");
            }
        };
        String rejectedId = UUID.randomUUID().toString(), failedId = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> {
                assertThatThrownBy(() -> storage.save(failedId, List.of(blocking, blocking, blocking))).isInstanceOf(IOException.class);
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> storage.save(rejectedId, List.of(blocking, blocking, blocking)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            assertThat(root.resolve("pictures").resolve(rejectedId)).doesNotExist();
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            assertThat(root.resolve("pictures").resolve(failedId)).doesNotExist();
            var valid = new MockMultipartFile("face", "face.jpg", "image/jpeg", image(64, 64, false));
            assertThat(storage.save(UUID.randomUUID().toString(), List.of(valid, valid, valid))).exists();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private byte[] image(int width, int height, boolean transparency) throws IOException {
        BufferedImage image = new BufferedImage(width, height, transparency ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = image.createGraphics();
        if (transparency) { graphics.setColor(Color.RED); graphics.fillRect(width / 2, 0, width / 2, height); }
        else { graphics.setColor(Color.GRAY); graphics.fillRect(0, 0, width, height); }
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, transparency ? "png" : "jpg", bytes); image.flush(); return bytes.toByteArray();
    }
}
