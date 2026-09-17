package com.example.smart_door_security_server;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

@Service
public class DeviceRegistrationService {
    private final PairedDeviceRepository devices;
    private final UserRepository users;
    private final AppSessionService sessions;
    private final PairingRateLimiter limits;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();
    public DeviceRegistrationService(PairedDeviceRepository devices, UserRepository users,
            AppSessionService sessions, PairingRateLimiter limits, PlatformTransactionManager manager) {
        this.devices = devices; this.users = users; this.sessions = sessions; this.limits = limits;
        transaction = new TransactionTemplate(manager);
    }
    public record EnrollRequest(DeviceRole role, String name) { }
    public record DeviceView(String deviceId, DeviceRole role, String name, boolean paired, Integer userNo,
            String pairingCode, Instant expiresAt) { }
    public record Preview(DeviceRole role, String name, Instant expiresAt) { }
    public record DeviceIdentity(String deviceId, DeviceRole role, Integer userNo) { }
    public record CameraCredential(String deviceId, Integer userNo, String tokenHash) { }

    public synchronized DeviceView enroll(String authorization, EnrollRequest request, String address) {
        String hash = TokenSecrets.hash(TokenSecrets.bearer(authorization));
        if (request.role() == null || request.name() == null || request.name().isBlank()
                || request.name().length() > 100) throw error(HttpStatus.BAD_REQUEST, "장치 역할과 100자 이하 이름이 필요합니다.");
        PairedDevice known = devices.findByTokenHash(hash).orElse(null);
        if (known == null || (known.getUserNo() == null && (known.getPairingExpiresAt() == null
                || !known.getPairingExpiresAt().isAfter(Instant.now())))) {
            limits.consume("enroll-ip:" + address, 30);
        }
        try { return transaction.execute(status -> enrollLocked(hash, request)); }
        catch (DataIntegrityViolationException race) {
            // The unique token/code constraints also protect parallel Render instances.
            return transaction.execute(status -> enrollLocked(hash, request));
        }
    }

    private DeviceView enrollLocked(String hash, EnrollRequest request) {
        PairedDevice device = devices.findTokenForUpdate(hash).orElse(null);
        if (device == null) {
            device = new PairedDevice(); device.setDeviceId(UUID.randomUUID().toString());
            device.setTokenHash(hash); device.setRole(request.role()); device.setName(request.name().strip());
            device.setCreatedAt(Instant.now());
        } else {
            if (device.getRole() != request.role()) throw error(HttpStatus.CONFLICT, "등록한 장치 역할은 변경할 수 없습니다.");
        }
        if (device.getUserNo() == null && (device.getPairingExpiresAt() == null
                || !device.getPairingExpiresAt().isAfter(Instant.now()))) {
            String code;
            do { code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000)); }
            while (devices.existsByPairingCode(code));
            device.setPairingCode(code);
            device.setPairingExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));
        }
        devices.saveAndFlush(device);
        return view(device, true);
    }

    @Transactional(readOnly = true)
    public DeviceView status(String authorization) {
        return view(findKnown(authorization).orElseThrow(TokenSecrets::unauthorized), false);
    }

    public Preview preview(String authorization, String code, String address) {
        Integer owner = sessions.requireUser(authorization);
        countAttempt(owner, address);
        PairedDevice device = pendingCode(code);
        return new Preview(device.getRole(), device.getName(), device.getPairingExpiresAt());
    }

    @Transactional
    public DeviceView claim(String authorization, String code, String address) {
        Integer owner = sessions.requireUser(authorization);
        countAttempt(owner, address);
        // Owner first: serialize role assignment with other pairings and user-owned writes.
        users.findForUpdateByUserNo(owner).orElseThrow(TokenSecrets::unauthorized);
        if (code == null || !code.matches("[0-9]{8}")) throw error(HttpStatus.BAD_REQUEST, "연결 코드는 8자리 숫자입니다.");
        PairedDevice device = devices.findCodeForUpdate(code)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "연결 코드를 찾을 수 없습니다."));
        if (device.getUserNo() != null || !Objects.equals(code, device.getPairingCode())) {
            throw error(HttpStatus.CONFLICT, "이미 사용된 연결 코드입니다.");
        }
        requireNotExpired(device);
        if (devices.findByUserNoAndRole(owner, device.getRole()).isPresent()) {
            throw error(HttpStatus.CONFLICT, "같은 역할의 장치가 이미 연결되어 있습니다. 기존 연결을 먼저 해제해 주세요.");
        }
        device.setUserNo(owner); device.setPairingCode(null); device.setPairingExpiresAt(null);
        devices.saveAndFlush(device);
        return view(device, false);
    }

    @Transactional(readOnly = true)
    public List<DeviceView> list(String authorization) {
        return devices.findByUserNoOrderByRole(sessions.requireUser(authorization)).stream()
                .map(device -> view(device, false)).toList();
    }

    @Transactional
    public void unlink(String authorization, String deviceId) {
        Integer owner = sessions.requireUser(authorization);
        users.findForUpdateByUserNo(owner).orElseThrow(TokenSecrets::unauthorized);
        PairedDevice device = devices.findForUpdate(deviceId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "연결된 장치를 찾을 수 없습니다."));
        if (!owner.equals(device.getUserNo())) throw error(HttpStatus.NOT_FOUND, "연결된 장치를 찾을 수 없습니다.");
        device.setUserNo(null); device.setPairingCode(null); device.setPairingExpiresAt(null);
        if (device.getRole() == DeviceRole.CAMERA) VideoStreamingController.clearOwnerFrame(owner);
    }

    @Transactional(readOnly = true)
    public Optional<PairedDevice> findKnown(String authorization) {
        if (authorization == null) return Optional.empty();
        return devices.findByTokenHash(TokenSecrets.hash(TokenSecrets.bearer(authorization)));
    }

    @Transactional(readOnly = true)
    public DeviceIdentity require(String authorization, DeviceRole role, Integer requestedOwner) {
        PairedDevice device = findKnown(authorization).orElseThrow(TokenSecrets::unauthorized);
        return identity(device, role, requestedOwner);
    }

    @Transactional(readOnly = true)
    public CameraCredential requireCameraCredential(String authorization) {
        PairedDevice device = findKnown(authorization).orElseThrow(TokenSecrets::unauthorized);
        var camera = identity(device, DeviceRole.CAMERA, null);
        // Reuse this request's checked identity, including the original credential binding.
        return new CameraCredential(camera.deviceId(), camera.userNo(), device.getTokenHash());
    }

    @Transactional(readOnly = true)
    public boolean hasRole(Integer owner, DeviceRole role) {
        return owner != null && devices.findByUserNoAndRole(owner, role).isPresent();
    }

    @Transactional(readOnly=true)
    public DeviceIdentity requireCameraSession(String deviceId, Integer owner, String tokenHash) {
        if (deviceId == null || owner == null || tokenHash == null) throw TokenSecrets.unauthorized();
        PairedDevice device = devices.findById(deviceId).orElseThrow(TokenSecrets::unauthorized);
        if (!device.getTokenHash().equals(tokenHash)) throw TokenSecrets.unauthorized();
        return identity(device, DeviceRole.CAMERA, owner);
    }

    DeviceIdentity identity(PairedDevice device, DeviceRole role, Integer requestedOwner) {
        if (device.getUserNo() == null || device.getRole() != role || !users.existsById(device.getUserNo())
                || (requestedOwner != null && !requestedOwner.equals(device.getUserNo()))) {
            throw error(HttpStatus.FORBIDDEN, "장치의 연결 계정 또는 역할이 요청과 일치하지 않습니다.");
        }
        return new DeviceIdentity(device.getDeviceId(), device.getRole(), device.getUserNo());
    }

    private void countAttempt(Integer owner, String address) {
        limits.consume("pair-account:" + owner, 10);
        limits.consume("pair-ip:" + address, 30);
    }
    private PairedDevice pendingCode(String code) {
        if (code == null || !code.matches("[0-9]{8}")) throw error(HttpStatus.BAD_REQUEST, "연결 코드는 8자리 숫자입니다.");
        PairedDevice device = devices.findByPairingCode(code)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "연결 코드를 찾을 수 없습니다."));
        if (device.getUserNo() != null) throw error(HttpStatus.CONFLICT, "이미 연결된 장치입니다.");
        requireNotExpired(device);
        return device;
    }
    private void requireNotExpired(PairedDevice device) {
        if (device.getPairingExpiresAt() == null || !device.getPairingExpiresAt().isAfter(Instant.now())) {
            throw error(HttpStatus.GONE, "연결 코드가 만료되었습니다. 장치에서 새 코드를 받아 주세요.");
        }
    }
    private static DeviceView view(PairedDevice d, boolean includeCode) {
        return new DeviceView(d.getDeviceId(), d.getRole(), d.getName(), d.getUserNo() != null, d.getUserNo(),
                includeCode && d.getUserNo() == null ? d.getPairingCode() : null,
                includeCode && d.getUserNo() == null ? d.getPairingExpiresAt() : null);
    }
    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
