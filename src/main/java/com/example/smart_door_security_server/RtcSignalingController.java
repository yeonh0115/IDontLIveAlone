package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/rtc") @RequiredArgsConstructor
public class RtcSignalingController {
    private final RtcSignalingService signaling;

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableJson() {
        // Default converter diagnostics can contain fragments of a private SDP document.
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_signaling_json"));
    }

    @PostMapping("/sessions")
    public ResponseEntity<RtcSignalingService.Status> create(
            @RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody RtcSignalingService.SdpRequest request) {
        return ResponseEntity.status(201).body(signaling.create(authorization, request));
    }
    @GetMapping("/sessions/{id}")
    public RtcSignalingService.Status status(@RequestHeader(value="Authorization", required=false) String authorization,
                                             @PathVariable String id) { return signaling.viewerStatus(authorization, id); }
    @PostMapping("/sessions/{id}/close")
    public ResponseEntity<Void> close(@RequestHeader(value="Authorization", required=false) String authorization,
                                      @PathVariable String id) {
        signaling.close(authorization, id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/camera/next")
    public ResponseEntity<RtcSignalingService.Offer> next(@RequestHeader(value="Authorization", required=false) String authorization) {
        return signaling.next(authorization).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
    @PostMapping("/camera/sessions/{id}/answer")
    public ResponseEntity<Void> answer(@RequestHeader(value="Authorization", required=false) String authorization,
                                       @PathVariable String id, @RequestBody RtcSignalingService.SdpRequest request) {
        signaling.answer(authorization, id, request); return ResponseEntity.noContent().build();
    }
    @GetMapping("/camera/sessions/{id}")
    public RtcSignalingService.Status cameraStatus(@RequestHeader(value="Authorization", required=false) String authorization,
                                                   @PathVariable String id) { return signaling.cameraStatus(authorization, id); }
}
