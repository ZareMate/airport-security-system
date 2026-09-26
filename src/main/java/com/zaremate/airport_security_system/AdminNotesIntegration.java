package com.zaremate.airport_security_system;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * Optional integration with the Admin Notes mod.
 *
 * <p>ASS owns its offense storage. This class only mirrors that data into the
 * Admin Notes {@code [ASS]} category when the optional API is installed.</p>
 */
public final class AdminNotesIntegration {
    private static final String API_CLASS_NAME =
            "com.zaremate.admin_notes.AdminNotesAPI";

    private static volatile boolean initialized;
    private static volatile boolean available;

    private static Method upsertSystemCategoryNoteMethod;

    private AdminNotesIntegration() {}

    /**
     * Mirrors the authoritative ASS state into the dedicated [ASS] category.
     *
     * <p>The category is updated through the public Admin Notes API and has no
     * user-facing note ID.</p>
     */
    public static void recordCheckResult(UUID playerUuid, java.util.Set<String> detectedKeys) {
        syncPlayerFromStore(playerUuid);
    }

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
                    text.append("\n");
                }

                text.append(entry.getKey())
                        .append(" detected (last: ")
                        .append(entry.getValue())
                        .append(")");
            }

            if ("CLEAN".equals(record.getStatus())
                    && record.getClearedDate() != null) {
                if (text.length() > 0) {
                    text.append("\n");
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

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }

        return throwable;
    }
}
