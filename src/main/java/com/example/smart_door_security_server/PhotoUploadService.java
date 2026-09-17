package com.example.smart_door_security_server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
public class PhotoUploadService {
    private final UserRepository users;
    private final DailyReportRepository reports;
    private final EventPhotoRepository photos;
    private final IntegratedLogRepository logs;
    private final DeviceEventReceiptRepository deviceEvents;
    private final EventPhotoSettings settings;
    private final Path uploadDir;
    private final String publicBaseUrl;

    public PhotoUploadService(UserRepository users, DailyReportRepository reports,
            EventPhotoRepository photos, IntegratedLogRepository logs, DeviceEventReceiptRepository deviceEvents, EventPhotoSettings settings,
            @Value("${app.storage-dir:./data}") String storageDir,
            @Value("${app.public-base-url:https://idontlivealone.onrender.com}") String publicBaseUrl) {
        this.users = users;
        this.reports = reports;
        this.photos = photos;
        this.logs = logs;
        this.deviceEvents = deviceEvents;
        this.settings = settings;
        this.uploadDir = Path.of(storageDir).toAbsolutePath().normalize().resolve("uploads");
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public record UploadResult(String url, String filename, boolean duplicate) {}

    @Transactional
    public UploadResult save(MultipartFile file, Integer userNo, String eventId, LocalDate date) {
        settings.requireEnabled();
        if (userNo == null || userNo <= 0 || date == null || eventId == null
                || !eventId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 사용자와 이벤트 정보가 필요합니다.");
        }
        // Serialize all report creation and appends for this owner, including a day's first photo.
        User user = users.findForUpdateByUserNo(userNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        EventPhoto existing = photos.findByUserNoAndSourceEventId(userNo, eventId).orElse(null);
        if (existing != null) {
            // The first receipt owns the event date. An ACK retry can cross midnight.
            String url = existing.getPhotoUrl();
            return new UploadResult(url, url.substring(url.lastIndexOf('/') + 1), true);
        }

        // A delayed photo belongs to the authenticated event's KST date, not the retry date.
        LocalDate eventDate = deviceEvents.findByUserNoAndSourceEventId(userNo, eventId)
                .map(DeviceEventReceipt::getEventDate).orElse(date);

        BufferedImage image = readImage(file);
        String filename = UUID.randomUUID() + ".jpg";
        Path target = uploadDir.resolve(filename);
        try {
            Files.createDirectories(uploadDir);
            if (!ImageIO.write(image, "jpg", target.toFile())) throw new IOException("JPEG encoder unavailable");
        } catch (IOException e) {
            removeFile(target);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "사진 파일을 저장하지 못했습니다.");
        }
        // Also remove this request's file if the transaction fails during its final commit.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) removeFile(target);
                }
            });
        }

        try {
            String url = publicBaseUrl + "/uploads/" + filename;
            List<IntegratedLog> dayLogs = logs.findByUserNoAndCreatedAtBetweenOrderByCreatedAtDesc(
                    userNo, eventDate.atStartOfDay(), eventDate.atTime(LocalTime.MAX));
            DailyReport report = reports.findByUserAndReportDate(user, eventDate).orElseGet(() -> {
                DailyReport created = new DailyReport();
                created.setUser(user);
                created.setReportDate(eventDate);
                created.setReportText("이벤트 사진이 저장되었습니다. 위험 여부는 감지 로그를 확인해 주세요.");
                return created;
            });
            report.setTotalEvents(dayLogs.size());
            report.setHighRiskEvents((int) dayLogs.stream()
                    .filter(log -> log.getSeverity() == IntegratedLog.Severity.high).count());
            LinkedHashSet<String> imageUrls = new LinkedHashSet<>();
            if (report.getPhotoUrl() != null && !report.getPhotoUrl().isBlank()) {
                Arrays.stream(report.getPhotoUrl().split(",")).map(String::trim)
                        .filter(value -> !value.isEmpty()).forEach(imageUrls::add);
            }
            imageUrls.add(url);
            report.setPhotoUrl(String.join(",", imageUrls));
            reports.save(report);
            EventPhoto photo = new EventPhoto();
            photo.setUserNo(userNo);
            photo.setSourceEventId(eventId);
            photo.setEventDate(eventDate);
            photo.setPhotoUrl(url);
            photos.saveAndFlush(photo);
            return new UploadResult(url, filename, false);
        } catch (RuntimeException e) {
            removeFile(target);
            throw e;
        }
    }

    private BufferedImage readImage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진은 5MB 이하의 JPEG 또는 PNG여야 합니다.");
        }
        try (var input = file.getInputStream(); ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            if (stream == null) throw new IOException("Empty image stream");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Unsupported image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName();
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!(format.equalsIgnoreCase("jpeg") || format.equalsIgnoreCase("png"))
                        || width <= 0 || height <= 0 || (long) width * height > 16_000_000) {
                    throw new IOException("Unsupported dimensions or format");
                }
                BufferedImage decoded = reader.read(0);
                BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                var graphics = rgb.createGraphics();
                try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); }
                return rgb;
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 사진 파일이 아닙니다.");
        }
    }

    private static void removeFile(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { /* safe orphan; no successful DB record */ }
    }
}
