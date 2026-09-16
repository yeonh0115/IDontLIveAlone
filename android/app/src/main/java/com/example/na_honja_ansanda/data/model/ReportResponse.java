package com.example.na_honja_ansanda.data.model;

import com.google.gson.annotations.SerializedName;

public class ReportResponse {
    @SerializedName("id")
    private Integer id;

    // 백엔드 JSON 키값(report_date 또는 reportDate) 대응
    @SerializedName(value = "reportDate", alternate = {"report_date"})
    private String reportDate;

    @SerializedName(value = "totalEvents", alternate = {"total_events"})
    private Integer totalEvents;

    @SerializedName(value = "highRiskEvents", alternate = {"high_risk_events"})
    private Integer highRiskEvents;

    @SerializedName(value = "reportText", alternate = {"report_text"})
    private String reportText;

    // 백엔드 JSON 키값(photoUrl 또는 photo_url) 모두 대응하도록 alternate 추가
    @SerializedName(value = "photoUrl", alternate = {"photo_url"})
    private String photoUrl;

    // 💡 [시간 표시 핵심] DB의 created_at 컬럼을 받아올 필드 추가
    @SerializedName(value = "createdAt", alternate = {"created_at"})
    private String createdAt;

    // --- Getter 메서드들 ---

    public Integer getId() { return id; }

    public String getReportDate() { return reportDate != null ? reportDate : "날짜 없음"; }

    public Integer getTotalEvents() { return totalEvents != null ? totalEvents : 0; }

    public Integer getHighRiskEvents() { return highRiskEvents != null ? highRiskEvents : 0; }

    public String getReportText() { return reportText != null ? reportText : ""; }

    public String getPhotoUrl() { return photoUrl; }

    // 💡 [시간 표시 핵심] ReportAdapter에서 시간을 꺼낼 때 사용할 Getter
    public String getCreatedAt() { return createdAt; }
}