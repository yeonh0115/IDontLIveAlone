package com.example.smart_door_security_server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Component
public class FaceImageStorage {
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private final Path pictureRoot;

    public FaceImageStorage(@Value("${app.storage-dir:./data}") String storageDirectory) {
        pictureRoot = Path.of(storageDirectory).toAbsolutePath().normalize().resolve("pictures");
    }

    public Path save(String taskId, List<MultipartFile> files) throws IOException {
        UUID.fromString(taskId);
        if (files == null || files.size() != 3 || files.stream().anyMatch(Objects::isNull)) {
            throw badRequest("얼굴 사진 3장이 필요합니다.");
        }
        Path taskDirectory = pictureRoot.resolve(taskId).normalize();
        if (!taskDirectory.startsWith(pictureRoot)) throw new IllegalArgumentException("Invalid image directory");
        Files.createDirectories(taskDirectory);
        try {
            for (int i = 0; i < files.size(); i++) {
                saveImage(files.get(i), taskDirectory.resolve("face" + (i + 1) + ".jpg"));
            }
            return taskDirectory;
        } catch (IOException | RuntimeException ex) {
            delete(taskDirectory);
            throw ex;
        }
    }

    private static void saveImage(MultipartFile file, Path destination) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_IMAGE_BYTES) {
            throw badRequest("각 얼굴 사진은 비어 있지 않은 10MB 이하 이미지여야 합니다.");
        }
        try (InputStream stream = file.getInputStream(); ImageInputStream imageStream = ImageIO.createImageInputStream(stream)) {
            if (imageStream == null) throw badRequest("얼굴 사진을 읽을 수 없습니다.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageStream);
            if (!readers.hasNext()) throw badRequest("JPEG 또는 PNG 얼굴 사진이 필요합니다.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageStream, true, true);
                String format = reader.getFormatName();
                if (!"JPEG".equalsIgnoreCase(format) && !"PNG".equalsIgnoreCase(format)) {
                    throw badRequest("JPEG 또는 PNG 얼굴 사진이 필요합니다.");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 32 || height < 32 || width > 8192 || height > 8192 || (long) width * height > 20_000_000) {
                    throw badRequest("얼굴 사진은 가로·세로 32~8192 픽셀, 총 2천만 픽셀 이하여야 합니다.");
                }
                BufferedImage source = reader.read(0);
                BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = rgb.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, width, height);
                    graphics.drawImage(source, 0, 0, null);
                } finally {
                    graphics.dispose();
                    source.flush();
                }
                try {
                    if (!ImageIO.write(rgb, "jpg", destination.toFile())) throw new IOException("JPEG writer unavailable");
                } finally {
                    rgb.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (javax.imageio.IIOException ex) {
            throw badRequest("손상된 얼굴 사진은 등록할 수 없습니다.");
        }
    }

    public void delete(Path taskDirectory) {
        Path normalized = taskDirectory.toAbsolutePath().normalize();
        if (!normalized.startsWith(pictureRoot) || normalized.equals(pictureRoot)) return;
        try (Stream<Path> files = Files.walk(normalized)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { /* Preserve the original error. */ }
            });
        } catch (IOException ignored) {
            // Cleanup must not mask the original registration failure.
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
