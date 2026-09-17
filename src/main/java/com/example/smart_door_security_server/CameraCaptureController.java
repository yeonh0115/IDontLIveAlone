package com.example.smart_door_security_server;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController @RequestMapping("/api/camera/captures") @RequiredArgsConstructor
public class CameraCaptureController {
    private final DeviceRegistrationService devices;
    private final CameraCaptureService captures;
    public record ResultRequest(String leaseToken, String status, String message) { }
    @GetMapping
    public ResponseEntity<CameraCaptureService.CaptureLease> claim(
            @RequestHeader(value="Authorization", required=false) String authorization) {
        var lease = captures.claim(devices.require(authorization, DeviceRole.CAMERA, null));
        return lease == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(lease);
    }
    @PostMapping("/{id}/result")
    public Map<String, String> result(@RequestHeader(value="Authorization", required=false) String authorization,
            @PathVariable String id, @RequestBody ResultRequest request) {
        captures.result(devices.require(authorization, DeviceRole.CAMERA, null), id, request.leaseToken(), request.status(), request.message());
        return Map.of("status", "success");
    }
}
