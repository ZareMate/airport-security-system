package com.zaremate.airport_security_system;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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

    private static Method getNotesMethod;
    private static Method addSystemNoteMethod;
    private static Method editNoteMethod;
    private static Method removeNoteMethod;

    private AdminNotesIntegration() {}

    /**
     * Updates the player's Admin Notes after a completed security check.
     *
     * <p>Airport Security System keeps exactly one system note per player for
     * its detection history. Multiple detections are stored together:</p>
     *
     * <pre>
     * x-ray detected
     * freecam detected
     * (last: 26-09-2026)
     * </pre>
     *
     * <p>A clean check uses the same single system note:</p>
     *
     * <pre>cleared (last: 26-09-2026)</pre>
     *
     * <p>Existing manually authored Admin Notes are never changed.</p>
     */
    public static void recordCheckResult(UUID playerUuid, Set<String> detectedKeys) {
        if (playerUuid == null || !initialize()) {
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            List<Object> notes = (List<Object>) getNotesMethod.invoke(null, playerUuid);
            String today = LocalDate.now().format(DATE_FORMAT);

            if (detectedKeys != null && !detectedKeys.isEmpty()) {
                Set<String> categories = new LinkedHashSet<>();

                for (String key : detectedKeys) {
                    String category = detectionCategory(key);
                    if (!category.isBlank()) {
                        categories.add(category);
                    }
                }

                if (!categories.isEmpty()) {
                    upsertDetectionNote(playerUuid, notes, categories, today);
                }

                return;
            }

            upsertClearedNote(playerUuid, notes, today);
        } catch (Throwable ex) {
            AirportSecuritySystem.LOGGER.warn(
                    "[Airport Security System] Failed to update Admin Notes for {}.",
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

                getNotesMethod = apiClass.getMethod("getNotes", UUID.class);
                addSystemNoteMethod = apiClass.getMethod(
                        "addSystemNote",
                        UUID.class,
                        String.class
                );
                editNoteMethod = apiClass.getMethod(
                        "editNote",
                        UUID.class,
                        UUID.class,
                        String.class
                );
                removeNoteMethod = apiClass.getMethod(
                        "removeNote",
                        UUID.class,
                        UUID.class
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

    /**
     * Creates/updates the single ASS system detection note.
     *
     * <p>If older versions created one note per category, those notes are
     * consolidated into one note the next time that player is detected.</p>
     */
    private static void upsertDetectionNote(
            UUID playerUuid,
            List<Object> notes,
            Set<String> newCategories,
            String date
    ) throws ReflectiveOperationException {
        List<Object> detectionNotes = findDetectionNotes(notes);

        Set<String> allCategories = new LinkedHashSet<>();

        for (Object note : detectionNotes) {
            allCategories.addAll(parseDetectionCategories(noteText(note)));
        }

        allCategories.addAll(newCategories);

        String formatted = detectionNote(allCategories, date);

        if (detectionNotes.isEmpty()) {
            addSystemNoteMethod.invoke(null, playerUuid, formatted);
            return;
        }

        Object primary = detectionNotes.get(0);
        UUID primaryId = noteId(primary);

        if (primaryId != null) {
            editNoteMethod.invoke(null, playerUuid, primaryId, formatted);
        }

        // Delete every additional ASS-generated detection note, including
        // old per-category notes from previous versions.
        for (int i = 1; i < detectionNotes.size(); i++) {
            UUID duplicateId = noteId(detectionNotes.get(i));
            if (duplicateId != null) {
                removeNoteMethod.invoke(null, playerUuid, duplicateId);
            }
        }

        // Any old "cleared" ASS note is no longer valid after a detection.
        removeClearedNotes(playerUuid, notes);
    }

    private static void upsertClearedNote(
            UUID playerUuid,
            List<Object> notes,
            String date
    ) throws ReflectiveOperationException {
        List<Object> detectionNotes = findDetectionNotes(notes);
        List<Object> clearedNotes = findClearedNotes(notes);

        // A clean result replaces the ASS detection state with one cleared note.
        for (Object note : detectionNotes) {
            UUID noteId = noteId(note);
            if (noteId != null) {
                removeNoteMethod.invoke(null, playerUuid, noteId);
            }
        }

        if (clearedNotes.isEmpty()) {
            addSystemNoteMethod.invoke(
                    null,
                    playerUuid,
                    clearedNote(date)
            );
            return;
        }

        Object primary = clearedNotes.get(0);
        UUID primaryId = noteId(primary);

        if (primaryId != null) {
            editNoteMethod.invoke(
                    null,
                    playerUuid,
                    primaryId,
                    clearedNote(date)
            );
        }

        // Remove accidental/legacy duplicate cleared notes.
        for (int i = 1; i < clearedNotes.size(); i++) {
            UUID duplicateId = noteId(clearedNotes.get(i));
            if (duplicateId != null) {
                removeNoteMethod.invoke(null, playerUuid, duplicateId);
            }
        }
    }

    private static List<Object> findDetectionNotes(List<Object> notes)
            throws ReflectiveOperationException {
        List<Object> result = new ArrayList<>();

        for (Object note : notes) {
            if (!isSystemNote(note)) {
                continue;
            }

            String text = noteText(note);
            if (isAirportSecuritySystemDetectionNote(text)) {
                result.add(note);
            }
        }

        return result;
    }

    private static void removeClearedNotes(UUID playerUuid, List<Object> notes)
            throws ReflectiveOperationException {
        for (Object note : notes) {
            if (!isSystemNote(note)) {
                continue;
            }

            String text = noteText(note);
            if (!isClearedNote(text)) {
                continue;
            }

            UUID noteId = noteId(note);
            if (noteId != null) {
                removeNoteMethod.invoke(null, playerUuid, noteId);
            }
        }
    }

    private static List<Object> findClearedNotes(List<Object> notes)
            throws ReflectiveOperationException {
        List<Object> result = new ArrayList<>();

        for (Object note : notes) {
            if (isSystemNote(note) && isClearedNote(noteText(note))) {
                result.add(note);
            }
        }

        return result;
    }

    private static boolean isAirportSecuritySystemDetectionNote(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return !normalized.startsWith("cleared (last:")
                && normalized.contains("detected");
    }

    private static Set<String> parseDetectionCategories(String text) {
        Set<String> categories = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return categories;
        }

        String body = text.trim();

        int lastMarker = body.toLowerCase(Locale.ROOT).lastIndexOf(" (last:");
        if (lastMarker >= 0) {
            body = body.substring(0, lastMarker).trim();
        }

        for (String line : body.split("\\R")) {
            String value = line.trim();
            int detectedIndex = value.toLowerCase(Locale.ROOT).indexOf(" detected");

            if (detectedIndex > 0) {
                String category = value.substring(0, detectedIndex).trim();
                if (!category.isBlank()) {
                    categories.add(category);
                }
            }
        }

        return categories;
    }

    private static String detectionNote(Set<String> categories, String date) {
        if (categories.isEmpty()) {
            return "detected (last: " + date + ")";
        }

        StringBuilder result = new StringBuilder();
        boolean first = true;

        for (String category : categories) {
            if (!first) {
                result.append("\\n");
            }

            result.append(category).append(" detected");
            first = false;
        }

        result.append(" (last: ").append(date).append(")");
        return result.toString();
    }

    private static boolean isClearedNote(String text) {
        return text != null && text.regionMatches(
                true,
                0,
                "cleared (last:",
                0,
                "cleared (last:".length()
        );
    }

    private static boolean isSystemNote(Object note)
            throws ReflectiveOperationException {
        Method method = note.getClass().getMethod("isSystem");
        Object value = method.invoke(note);
        return Boolean.TRUE.equals(value);
    }

    private static UUID noteId(Object note) throws ReflectiveOperationException {
        Method method = note.getClass().getMethod("id");
        Object value = method.invoke(note);
        return value instanceof UUID uuid ? uuid : null;
    }

    private static String noteText(Object note) throws ReflectiveOperationException {
        Method method = note.getClass().getMethod("text");
        Object value = method.invoke(note);
        return value instanceof String text ? text : null;
    }

    private static String clearedNote(String date) {
        return "cleared (last: " + date + ")";
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
