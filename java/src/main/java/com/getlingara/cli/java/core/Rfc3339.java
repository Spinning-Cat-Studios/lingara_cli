package com.getlingara.cli.java.core;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** RFC 3339 in UTC, the one form the profile record's {@code access_expires_at} takes (D4). */
public final class Rfc3339 {
    private Rfc3339() {}

    /** Seconds since the epoch, as {@code YYYY-MM-DDTHH:MM:SSZ}. */
    public static String format(long secs) {
        return Instant.ofEpochSecond(secs).toString();
    }

    /**
     * {@code YYYY-MM-DDTHH:MM:SS} then {@code Z} or nothing; fractions are ignored, as the
     * reference kit reads it. {@code null} when it is not one.
     */
    public static Long parse(String text) {
        if (text == null || text.length() < 19) {
            return null;
        }
        String head = text.substring(0, 19).replace('t', 'T');
        try {
            return LocalDateTime.parse(head).toEpochSecond(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
