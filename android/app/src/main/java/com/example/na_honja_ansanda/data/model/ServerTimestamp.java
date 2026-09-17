package com.example.na_honja_ansanda.data.model;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;

/** Server LocalDateTime values use KST; explicit offsets keep their actual instant. */
public final class ServerTimestamp {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA);
    private ServerTimestamp() { }

    public static ZonedDateTime parse(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(normalized).atZoneSameInstant(KST);
        } catch (DateTimeParseException ignored) {
            try { return LocalDateTime.parse(normalized).atZone(KST); }
            catch (DateTimeParseException invalid) { return null; }
        }
    }

    public static Date toDate(String value) {
        ZonedDateTime parsed = parse(value);
        return parsed == null ? null : Date.from(parsed.toInstant());
    }

    public static String clock(String value) {
        ZonedDateTime parsed = parse(value);
        return parsed == null ? "--:--" : CLOCK.format(parsed);
    }
}
