package com.example.na_honja_ansanda.data.model;

import java.util.List;

/** Missing or unavailable evidence must never be presented as a safe day. */
public final class DailyReportStatus {
    public enum Kind { LOADING, ERROR, NO_DATA, SAFE, CAUTION, DANGER }
    private final Kind kind;
    private final int highRiskEvents;

    private DailyReportStatus(Kind kind, int highRiskEvents) {
        this.kind = kind;
        this.highRiskEvents = highRiskEvents;
    }

    public Kind getKind() { return kind; }
    public int getHighRiskEvents() { return highRiskEvents; }

    public static DailyReportStatus forDate(LoadState state, List<ReportResponse> reports, String date) {
        if (state == LoadState.LOADING) return new DailyReportStatus(Kind.LOADING, 0);
        if (state != LoadState.READY) return new DailyReportStatus(Kind.ERROR, 0);
        boolean found = false;
        int risk = 0;
        if (reports != null && date != null) {
            for (ReportResponse report : reports) {
                if (report != null && date.equals(report.getReportDate())) {
                    found = true;
                    risk += Math.max(0, report.getHighRiskEvents());
                }
            }
        }
        if (!found) return new DailyReportStatus(Kind.NO_DATA, 0);
        return new DailyReportStatus(risk == 0 ? Kind.SAFE : risk < 4 ? Kind.CAUTION : Kind.DANGER, risk);
    }
}
