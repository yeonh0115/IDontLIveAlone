package com.example.na_honja_ansanda;

import com.example.na_honja_ansanda.data.model.ServerTimestamp;
import java.time.LocalDate;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServerTimestampTest {
    @Test public void localServerTimestampStaysInKoreaTime() {
        assertEquals("10:15", ServerTimestamp.clock("2026-09-17T10:15:00"));
        assertEquals("10:15", ServerTimestamp.clock("2026-09-17 10:15:00.123456"));
    }

    @Test public void explicitOffsetsAndUtcConvertToActualKoreaDate() {
        assertEquals("10:15", ServerTimestamp.clock("2026-09-17T01:15:00Z"));
        assertEquals("10:15", ServerTimestamp.clock("2026-09-17T10:15:00+09:00"));
        assertEquals(LocalDate.of(2026, 9, 18), ServerTimestamp.parse("2026-09-17T23:30:00Z").toLocalDate());
    }

    @Test public void missingOrMalformedTimeIsNotReplacedWithNow() {
        for (String value : new String[]{null, "", "2026-09-17", "invalid", "2026-02-30T12:00:00"}) {
            assertNull(ServerTimestamp.toDate(value));
            assertEquals("--:--", ServerTimestamp.clock(value));
        }
    }
}
