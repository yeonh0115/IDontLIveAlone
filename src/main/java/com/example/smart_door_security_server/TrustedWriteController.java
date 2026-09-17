package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class TrustedWriteController {
    private final TrustedWriteService service;

    @PostMapping("/device/events")
    public TrustedWriteService.EventResponse saveEvent(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody TrustedWriteService.EventRequest request) {
        return service.saveEvent(authorization, request);
    }

    @PostMapping("/reports/generated")
    public TrustedWriteService.ReportResponse saveReport(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody TrustedWriteService.ReportRequest request) {
        return service.saveReport(authorization, request);
    }
}
