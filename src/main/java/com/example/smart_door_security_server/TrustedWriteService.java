package com.example.smart_door_security_server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class TrustedWriteService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final WriterAccessControl access;
    private final UserRepository users;
    private final IntegratedLogRepository logs;
    private final DeviceEventReceiptRepository events;
    private final GeneratedReportReceiptRepository submissions;
    private final DailyReportRepository reports;
    private final ReportAggregationService aggregation;
    private final CameraCaptureService captures;

    public record EventRequest(String eventId, Integer userNo, OffsetDateTime occurredAt,
            IntegratedLog.LogType logType, String subType, Float val1, Float val2,
            IntegratedLog.Severity severity, String description) { }
    public record EventResponse(boolean success, String eventId, Integer logId,
            Integer userNo, LocalDate eventDate, boolean duplicate,
            boolean cameraCaptureQueued, String captureTaskId, String captureStatus) { }
    public record ReportRequest(String requestId, Integer userNo, LocalDate reportDate, String reportText) { }
    public record ReportResponse(boolean success, Integer reportId, Integer userNo,
            LocalDate reportDate, boolean duplicate, Integer totalEvents, Integer highRiskEvents) { }

    @Transactional
    public EventResponse saveEvent(String authorization, EventRequest request) {
        var grant = access.resolveDevice(authorization, request.userNo());
        request = new EventRequest(request.eventId(), grant.userNo(), request.occurredAt(), request.logType(),
                request.subType(), request.val1(), request.val2(), request.severity(), request.description());
        requireId(request.eventId());
        if (request.occurredAt() == null || request.logType() == null || request.severity() == null
                || request.subType() == null || request.subType().isBlank() || request.subType().length() > 50
                || request.description() == null || request.description().length() > 8000
                || (request.val1() != null && !Float.isFinite(request.val1()))
                || (request.val2() != null && !Float.isFinite(request.val2()))) {
            throw badRequest("이벤트 시간, 종류, 상세 정보를 확인해 주세요.");
        }
        User user = lockOwner(request.userNo());
        String hash = RequestFingerprint.of(request.userNo().toString(), request.occurredAt().toInstant().toString(),
                request.logType().name(), request.subType(), value(request.val1()), value(request.val2()),
                request.severity().name(), request.description());
        DeviceEventReceipt existing = events.findByUserNoAndSourceEventId(request.userNo(), request.eventId()).orElse(null);
        if (existing != null) {
            if (!existing.getPayloadHash().equals(hash)) throw conflict("같은 eventId에 다른 내용을 저장할 수 없습니다.");
            return eventResponse(existing, true, captures.request(existing.getUserNo(), existing.getSourceEventId(),
                    existing.getEventDate(), request.occurredAt().toInstant(), grant.paired()));
        }
        var localTime = request.occurredAt().atZoneSameInstant(SEOUL).toLocalDateTime();
        IntegratedLog log = new IntegratedLog();
        log.setUserNo(user.getUserNo());
        log.setLogType(request.logType());
        log.setSubType(request.subType());
        log.setVal1(request.val1());
        log.setVal2(request.val2());
        log.setSeverity(request.severity());
        log.setDescription(request.description());
        log.setCreatedAt(localTime);
        logs.saveAndFlush(log);
        DeviceEventReceipt receipt = new DeviceEventReceipt();
        receipt.setUserNo(user.getUserNo());
        receipt.setSourceEventId(request.eventId());
        receipt.setLogId(log.getLogId());
        receipt.setEventDate(localTime.toLocalDate());
        receipt.setPayloadHash(hash);
        events.save(receipt);
        // Keep counts correct even if the camera is offline or the image arrives later.
        aggregation.refresh(user, receipt.getEventDate());
        return eventResponse(receipt, false, captures.request(receipt.getUserNo(), receipt.getSourceEventId(),
                receipt.getEventDate(), request.occurredAt().toInstant(), grant.paired()));
    }

    @Transactional
    public ReportResponse saveReport(String authorization, ReportRequest request) {
        var grant = access.resolveReport(authorization, request.userNo());
        request = new ReportRequest(request.requestId(), grant.userNo(), request.reportDate(), request.reportText());
        requireId(request.requestId());
        if (request.reportDate() == null || request.reportText() == null || request.reportText().isBlank()
                || request.reportText().getBytes(StandardCharsets.UTF_8).length > 60000) {
            throw badRequest("리포트 날짜와 60KB 이하의 리포트 본문이 필요합니다.");
        }
        User user = lockOwner(request.userNo());
        String hash = RequestFingerprint.of(request.userNo().toString(), request.reportDate().toString(), request.reportText());
        GeneratedReportReceipt existing = submissions.findByUserNoAndRequestId(request.userNo(), request.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.getPayloadHash().equals(hash)) throw conflict("같은 requestId에 다른 내용을 저장할 수 없습니다.");
            DailyReport report = reports.findById(existing.getReportId())
                    .orElseThrow(() -> conflict("이미 처리한 리포트가 더 이상 존재하지 않습니다."));
            return reportResponse(report, request.userNo(), true);
        }
        DailyReport report = aggregation.refresh(user, request.reportDate());
        report.setReportText(request.reportText());
        reports.saveAndFlush(report);
        GeneratedReportReceipt receipt = new GeneratedReportReceipt();
        receipt.setUserNo(user.getUserNo());
        receipt.setRequestId(request.requestId());
        receipt.setReportId(report.getId());
        receipt.setReportDate(request.reportDate());
        receipt.setPayloadHash(hash);
        submissions.save(receipt);
        return reportResponse(report, request.userNo(), false);
    }

    private User lockOwner(Integer userNo) {
        return users.findForUpdateByUserNo(userNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "설정된 사용자를 찾을 수 없습니다."));
    }

    private static EventResponse eventResponse(DeviceEventReceipt receipt, boolean duplicate, CameraCaptureService.CaptureState capture) {
        return new EventResponse(true, receipt.getSourceEventId(), receipt.getLogId(),
                receipt.getUserNo(), receipt.getEventDate(), duplicate,
                capture.cameraCaptureQueued(), capture.captureTaskId(), capture.captureStatus());
    }

    private static ReportResponse reportResponse(DailyReport report, Integer userNo, boolean duplicate) {
        return new ReportResponse(true, report.getId(), userNo, report.getReportDate(), duplicate,
                report.getTotalEvents(), report.getHighRiskEvents());
    }

    private static String value(Float number) { return number == null ? null : number.toString(); }
    private static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw badRequest("요청 ID 형식이 올바르지 않습니다.");
    }
    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
