package com.example.smart_door_security_server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class WriterAccessControl {
    private final String deviceToken;
    private final String deviceUserNo;
    private final String reportToken;
    private final String reportUserNo;
    private DeviceRegistrationService devices;

    @org.springframework.beans.factory.annotation.Autowired
    void setDevices(DeviceRegistrationService devices) { this.devices = devices; }

    public record AccessGrant(Integer userNo, boolean paired) { }

    public WriterAccessControl(@Value("${DEVICE_API_TOKEN:}") String deviceToken,
            @Value("${DEVICE_USER_NO:}") String deviceUserNo,
            @Value("${REPORT_API_TOKEN:}") String reportToken,
            @Value("${REPORT_USER_NO:}") String reportUserNo) {
        this.deviceToken = deviceToken;
        this.deviceUserNo = deviceUserNo;
        this.reportToken = reportToken;
        this.reportUserNo = reportUserNo;
    }

    public void requireDevice(String authorization, Integer userNo) {
        resolveDevice(authorization, userNo);
    }

    public void requireReport(String authorization, Integer userNo) {
        resolveReport(authorization, userNo);
    }

    public AccessGrant resolveDevice(String authorization, Integer userNo) {
        return resolve(authorization, userNo, DeviceRole.SENSOR, deviceToken, deviceUserNo);
    }

    public AccessGrant resolveReport(String authorization, Integer userNo) {
        return resolve(authorization, userNo, DeviceRole.REPORT, reportToken, reportUserNo);
    }

    private AccessGrant resolve(String authorization, Integer userNo, DeviceRole role, String token, String configuredUser) {
        if (devices != null) {
            var known = devices.findKnown(authorization);
            if (known.isPresent()) return new AccessGrant(devices.identity(known.get(), role, userNo).userNo(), true);
        }
        return new AccessGrant(require(authorization, userNo, token, configuredUser), false);
    }

    private static Integer require(String authorization, Integer userNo, String configuredToken, String configuredUser) {
        String normalizedToken = configuredToken == null ? "" : configuredToken.strip();
        Integer allowedUser = null;
        try {
            if (configuredUser != null) allowedUser = Integer.valueOf(configuredUser.strip());
        } catch (NumberFormatException ignored) { }
        if (normalizedToken.length() < 32 || allowedUser == null || allowedUser <= 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "요청 처리 계정이 설정되지 않았습니다.");
        }
        if (authorization == null || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(normalizedToken.getBytes(StandardCharsets.UTF_8),
                        authorization.substring(7).getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "요청 인증에 실패했습니다.");
        }
        if (userNo != null && !allowedUser.equals(userNo)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "설정된 사용자만 요청할 수 있습니다.");
        }
        return allowedUser;
    }
}
