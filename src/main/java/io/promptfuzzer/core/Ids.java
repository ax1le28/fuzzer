package io.promptfuzzer.core;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** Identifier helpers. Ids are safe to use as file names. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,80}");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private Ids() {
    }

    public static String slug(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (s.isEmpty()) {
            s = "task";
        }
        return s.length() > 48 ? s.substring(0, 48).replaceAll("-+$", "") : s;
    }

    /** A sortable, unique id such as {@code run-20261020-120344-a1b2}. */
    public static String timestamped(String prefix) {
        return prefix + "-" + STAMP.format(Instant.now()) + "-" + Integer.toHexString(0x10000 + RANDOM.nextInt(0xF0000));
    }

    public static boolean isSafe(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }
}
