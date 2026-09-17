package com.example.smart_door_security_server;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/devices") @RequiredArgsConstructor
public class DeviceController {
    private final DeviceRegistrationService devices;
    public record ClaimRequest(String code) { }
    @PostMapping("/enroll")
    public DeviceRegistrationService.DeviceView enroll(
            @RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody DeviceRegistrationService.EnrollRequest request, HttpServletRequest servlet) {
        return devices.enroll(authorization, request, servlet.getRemoteAddr());
    }
    @GetMapping("/status")
    public DeviceRegistrationService.DeviceView status(@RequestHeader(value="Authorization", required=false) String authorization) {
        return devices.status(authorization);
    }
    @GetMapping("/pairing/{code}")
    public DeviceRegistrationService.Preview preview(@RequestHeader(value="Authorization", required=false) String authorization,
            @PathVariable String code, HttpServletRequest servlet) {
        return devices.preview(authorization, code, servlet.getRemoteAddr());
    }
    @PostMapping("/claim")
    public DeviceRegistrationService.DeviceView claim(@RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody ClaimRequest request, HttpServletRequest servlet) {
        return devices.claim(authorization, request.code(), servlet.getRemoteAddr());
    }
    @GetMapping
    public List<DeviceRegistrationService.DeviceView> list(@RequestHeader(value="Authorization", required=false) String authorization) {
        return devices.list(authorization);
    }
    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> unlink(@RequestHeader(value="Authorization", required=false) String authorization,
            @PathVariable String deviceId) {
        devices.unlink(authorization, deviceId);
        return ResponseEntity.noContent().build();
    }
}
