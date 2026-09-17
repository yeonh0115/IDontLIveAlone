package com.example.na_honja_ansanda;

import com.example.na_honja_ansanda.data.model.DailyReportStatus;
import com.example.na_honja_ansanda.data.model.LoadState;
import com.example.na_honja_ansanda.data.model.ReportResponse;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailyReportStatusTest {
    private static final String DATE = "2026-09-16";
    private ReportResponse report(int risk) {
        return new Gson().fromJson("{\"reportDate\":\"" + DATE + "\",\"highRiskEvents\":" + risk + "}", ReportResponse.class);
    }

    @Test public void unavailableDataNeverMeansSafeEvenWithCachedData() {
        assertEquals(DailyReportStatus.Kind.ERROR,
                DailyReportStatus.forDate(LoadState.FAILED, Collections.singletonList(report(0)), DATE).getKind());
        assertEquals(DailyReportStatus.Kind.LOADING,
                DailyReportStatus.forDate(LoadState.LOADING, Collections.singletonList(report(0)), DATE).getKind());
    }

    @Test public void successfulEmptyResponseOrDifferentDateMeansNoData() {
        assertEquals(DailyReportStatus.Kind.NO_DATA,
                DailyReportStatus.forDate(LoadState.READY, Collections.emptyList(), DATE).getKind());
        assertEquals(DailyReportStatus.Kind.NO_DATA,
                DailyReportStatus.forDate(LoadState.READY, Collections.singletonList(report(0)), "2026-09-15").getKind());
    }

    @Test public void actualReportsDetermineSafeCautionAndDanger() {
        assertEquals(DailyReportStatus.Kind.SAFE,
                DailyReportStatus.forDate(LoadState.READY, Collections.singletonList(report(0)), DATE).getKind());
        assertEquals(DailyReportStatus.Kind.CAUTION,
                DailyReportStatus.forDate(LoadState.READY, Collections.singletonList(report(3)), DATE).getKind());
        DailyReportStatus aggregate = DailyReportStatus.forDate(LoadState.READY, Arrays.asList(report(2), report(2)), DATE);
        assertEquals(DailyReportStatus.Kind.DANGER, aggregate.getKind());
        assertEquals(4, aggregate.getHighRiskEvents());
    }
}
