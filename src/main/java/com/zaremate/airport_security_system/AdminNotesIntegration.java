package com.zaremate.airport_security_system;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

/**
 * Optional integration with the Admin Notes mod.
 *
 * <p>This class intentionally uses reflection so Airport Security System can
 * still run normally when Admin Notes is not installed.</p>
 */
public final class AdminNotesIntegration {
    private static final String API_CLASS_NAME =
            "com.zaremate.admin_notes.AdminNotesAPI";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static volatile boolean initialized;
    private static volatile boolean available;

    private static Method upsertSystemCategoryNoteMethod;

    private AdminNotesIntegration() {}

    public static void recordCheckResult(UUID playerUuid, Set<String> detectedKeys) {
        if (playerUuid == null || !initialize()) {
            return;
        }

        syncPlayerFromStore(playerUuid);
    }

    /**
     * Mirrors the authoritative ASS offense state into the dedicated [ASS]
     * Admin Notes category. The Admin Notes entry is only a display mirror;
     * ASS offense storage remains authoritative.
     */
    public static void syncPlayerFromStore(UUID playerUuid) {
        if (playerUuid == null || !initialize()) {
            return;
        }

        try {
            AirportSecuritySystemOffenses.PlayerRecord record =
                    AirportSecuritySystemOffenses.getPlayer(playerUuid);

            if (record == null) {
                return;
            }

            StringBuilder text = new StringBuilder();

            for (Map.Entry<String, String> entry :
                    record.getDetectionDates().entrySet()) {
                if (text.length() > 0) {
                    text.append("\\n");
                }

                text.append(entry.getKey())
                        .append(" detected (last: ")
                        .append(entry.getValue())
                        .append(")");
            }

            if ("CLEAN".equals(record.getStatus())
                    && record.getClearedDate() != null) {
                if (text.length() > 0) {
                    text.append("\\n");
                }

                text.append("cleared (last: ")
                        .append(record.getClearedDate())
                        .append(")");
            }

            if (text.length() == 0) {
                return;
            }

            upsertSystemCategoryNoteMethod.invoke(
                    null,
                    playerUuid,
                    "ASS",
                    text.toString()
            );
        } catch (Throwable ex) {
            AirportSecuritySystem.LOGGER.warn(
                    "[Airport Security System] Failed to sync [ASS] Admin Notes data for {}.",
                    playerUuid,
                    unwrap(ex)
            );
        }
    }

    private static boolean initialize() {
        if (initialized) {
            return available;
        }

        synchronized (AdminNotesIntegration.class) {
            if (initialized) {
                return available;
            }

            initialized = true;

            try {
                Class<?> apiClass = Class.forName(API_CLASS_NAME);

                upsertSystemCategoryNoteMethod = apiClass.getMethod(
                        "upsertSystemCategoryNote",
                        UUID.class,
                        String.class,
                        String.class
                );

                available = true;

                AirportSecuritySystem.LOGGER.info(
                        "[Airport Security System] Admin Notes integration enabled."
                );
            } catch (Throwable ex) {
                available = false;
                AirportSecuritySystem.LOGGER.debug(
                        "[Airport Security System] Admin Notes is not installed; note integration disabled."
                );
            }

            return available;
        }
    }

    private static String detectionCategory(String key) {
        if (key == null) {
            return "";
        }

        String value = key.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return "";
        }

        if (value.contains("xray") || value.contains("x-ray")) {
            return "x-ray";
        }

        if (value.contains("chestesp") || value.contains("chest esp")) {
            return "chest esp";
        }

        if (value.contains("esp")) {
            return "esp";
        }

        if (value.contains("killaura") || value.contains("kill-aura")) {
            return "kill aura";
        }

        if (value.contains("freecam")) {
            return "freecam";
        }

        if (value.contains("autoclick")) {
            return "auto-clicker";
        }

        if (value.contains("autofish")) {
            return "auto-fish";
        }

        if (value.contains("antiafk") || value.contains("anti-afk")) {
            return "anti-afk";
        }

        if (value.contains("autoswitch")) {
            return "auto-switch";
        }

        if (value.contains("trouser")) {
            return "trouser-streak";
        }

        if (value.contains("baritone")) {
            return "baritone";
        }

        String cleaned = value
                .replace(':', '.')
                .replace('_', ' ')
                .replace('-', ' ');

        String[] parts = cleaned.split("\\.");
        for (String part : parts) {
            String candidate = part.trim();

            if (candidate.isEmpty()
                    || candidate.equals("key")
                    || candidate.equals("module")
                    || candidate.equals("addon")
                    || candidate.equals("translate")
                    || candidate.equals("keybind")
                    || candidate.equals("meteor")) {
                continue;
            }

            if (candidate.length() > 2) {
                return candidate;
            }
        }

        return cleaned.trim();
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }

        return throwable;
    }
}
