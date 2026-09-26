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
    /**
     * Returns the current Airport Security System offense state for a player.
     * The returned map contains normalized detection categories. A null map
     * means the player has no ASS system note or the integration is unavailable.
     */
    public static Map<String, Object> getPlayerOffenseState(UUID playerUuid) {
        if (playerUuid == null || !initialize()) {
            return null;
        }

        try {
            @SuppressWarnings("unchecked")
            List<Object> notes = (List<Object>) getNotesMethod.invoke(null, playerUuid);

            LinkedHashMap<String, String> detections = new LinkedHashMap<>();
            String clearedDate = null;

            for (Object note : findAssSystemNotes(notes)) {
                String text = noteText(note);
                mergeDetectionDates(detections, parseDetectionDates(text));
                String date = parseClearedDate(text);
                if (isLaterDate(date, clearedDate)) {
                    clearedDate = date;
                }
            }

            if (clearedDate != null) {
                return Map.of(
                        "status", "CLEAN",
                        "categories", detections.keySet(),
                        "last", clearedDate
                );
            }

            if (!detections.isEmpty()) {
                String latestDate = latestDetectionDate(detections);
                return Map.of(
                        "status", "DETECTED",
                        "categories", detections.keySet(),
                        "last", latestDate == null ? "Unknown" : latestDate
                );
            }

            return null;
        } catch (Throwable ex) {
            AirportSecuritySystem.LOGGER.debug(
                    "[Airport Security System] Failed to read Admin Notes offense state for {}.",
                    playerUuid,
                    unwrap(ex)
            );
            return null;
        }
    }

    public static void recordCheckResult(UUID playerUuid, Set<String> detectedKeys) {
        if (playerUuid == null || !initialize()) {
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            List<Object> notes = (List<Object>) getNotesMethod.invoke(null, playerUuid);
            String today = LocalDate.now().format(DATE_FORMAT);

            Set<String> categories = new LinkedHashSet<>();
            if (detectedKeys != null) {
                for (String key : detectedKeys) {
                    String category = detectionCategory(key);
                    if (!category.isBlank()) {
                        categories.add(category);
                    }
                }
            }

            if (!categories.isEmpty()) {
                upsertDetectionNote(playerUuid, notes, categories, today);
            } else {
                upsertClearedNote(playerUuid, notes, today);
            }
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
     * Updates the one ASS note while preserving every previously detected
     * category and its own last-detected date.
     *
     * <p>A later detection removes only the "cleared" line. Categories that
     * were not detected in the current check retain their previous dates.</p>
     */
    private static void upsertDetectionNote(
            UUID playerUuid,
            List<Object> notes,
            Set<String> newCategories,
            String date
    ) throws ReflectiveOperationException {
        List<Object> assNotes = findAssSystemNotes(notes);

        LinkedHashMap<String, String> detectionDates = new LinkedHashMap<>();

        for (Object note : assNotes) {
            mergeDetectionDates(detectionDates, parseDetectionDates(noteText(note)));
        }

        for (String category : newCategories) {
            detectionDates.put(category, date);
        }

        String formatted = detectionNote(detectionDates);

        if (assNotes.isEmpty()) {
            addSystemNoteMethod.invoke(null, playerUuid, formatted);
            return;
        }

        Object primary = assNotes.get(0);
        UUID primaryId = noteId(primary);

        if (primaryId != null) {
            editNoteMethod.invoke(null, playerUuid, primaryId, formatted);
        }

        removeDuplicateAssNotes(playerUuid, assNotes, primary);
    }

    /**
     * Adds or refreshes the "cleared" line without deleting detection history.
     */
    private static void upsertClearedNote(
            UUID playerUuid,
            List<Object> notes,
            String date
    ) throws ReflectiveOperationException {
        List<Object> assNotes = findAssSystemNotes(notes);

        LinkedHashMap<String, String> detectionDates = new LinkedHashMap<>();
        for (Object note : assNotes) {
            mergeDetectionDates(detectionDates, parseDetectionDates(noteText(note)));
        }

        String formatted = detectionNoteWithCleared(detectionDates, date);

        if (assNotes.isEmpty()) {
            addSystemNoteMethod.invoke(null, playerUuid, formatted);
            return;
        }

        Object primary = assNotes.get(0);
        UUID primaryId = noteId(primary);

        if (primaryId != null) {
            editNoteMethod.invoke(null, playerUuid, primaryId, formatted);
        }

        removeDuplicateAssNotes(playerUuid, assNotes, primary);
    }

    private static void removeDuplicateAssNotes(
            UUID playerUuid,
            List<Object> assNotes,
            Object primary
    ) throws ReflectiveOperationException {
        UUID primaryId = noteId(primary);

        for (Object note : assNotes) {
            if (note == primary) {
                continue;
            }

            UUID duplicateId = noteId(note);
            if (duplicateId != null && !duplicateId.equals(primaryId)) {
                removeNoteMethod.invoke(null, playerUuid, duplicateId);
            }
        }
    }

    private static List<Object> findAssSystemNotes(List<Object> notes)
            throws ReflectiveOperationException {
        List<Object> result = new ArrayList<>();

        for (Object note : notes) {
            if (!isSystemNote(note)) {
                continue;
            }

            if (isAirportSecuritySystemNote(noteText(note))) {
                result.add(note);
            }
        }

        return result;
    }

    private static List<Object> findDetectionNotes(List<Object> notes)
            throws ReflectiveOperationException {
        List<Object> result = new ArrayList<>();

        for (Object note : findAssSystemNotes(notes)) {
            if (!parseDetectionDates(noteText(note)).isEmpty()) {
                result.add(note);
            }
        }

        return result;
    }

    private static List<Object> findClearedNotes(List<Object> notes)
            throws ReflectiveOperationException {
        List<Object> result = new ArrayList<>();

        for (Object note : findAssSystemNotes(notes)) {
            if (parseClearedDate(noteText(note)) != null) {
                result.add(note);
            }
        }

        return result;
    }

    private static boolean isAirportSecuritySystemNote(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text.trim().toLowerCase(Locale.ROOT);

        return normalized.startsWith("ass_check|")
                || normalized.contains(" detected")
                || normalized.contains("cleared (last:");
    }

    private static Map<String, String> parseDetectionDates(String text) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();

        if (text == null || text.isBlank()) {
            return result;
        }

        String commonDate = parseLatestDate(text);

        for (String line : text.trim().split("\\R")) {
            String value = line.trim();
            String normalized = value.toLowerCase(Locale.ROOT);

            if (normalized.startsWith("ass_check|")
                    || normalized.startsWith("cleared (last:")) {
                continue;
            }

            int detectedIndex = normalized.indexOf(" detected");
            if (detectedIndex <= 0) {
                continue;
            }

            String category = value.substring(0, detectedIndex).trim();
            if (category.isBlank()) {
                continue;
            }

            String date = parseLatestDate(value);
            if (date == null) {
                date = commonDate;
            }

            result.put(category, date);
        }

        return result;
    }

    private static String parseClearedDate(String text) {
        if (text == null) {
            return null;
        }

        for (String line : text.split("\\R")) {
            String value = line.trim();

            if (value.regionMatches(
                    true,
                    0,
                    "cleared (last:",
                    0,
                    "cleared (last:".length()
            )) {
                return parseLatestDate(value);
            }
        }

        return null;
    }

    private static void mergeDetectionDates(
            Map<String, String> target,
            Map<String, String> source
    ) {
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String existing = target.get(entry.getKey());
            if (existing == null || isLaterDate(entry.getValue(), existing)) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static String latestDetectionDate(Map<String, String> detections) {
        String latest = null;

        for (String date : detections.values()) {
            if (isLaterDate(date, latest)) {
                latest = date;
            }
        }

        return latest;
    }

    private static boolean isLaterDate(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }

        if (current == null || current.isBlank()) {
            return true;
        }

        try {
            LocalDate candidateDate = LocalDate.parse(candidate, DATE_FORMAT);
            LocalDate currentDate = LocalDate.parse(current, DATE_FORMAT);
            return candidateDate.isAfter(currentDate);
        } catch (Exception ignored) {
            return !candidate.equals(current);
        }
    }

    private static String detectionNote(Map<String, String> detections) {
        StringBuilder result = new StringBuilder();
        boolean first = true;

        for (Map.Entry<String, String> entry : detections.entrySet()) {
            if (!first) {
                result.append("\\n");
            }

            result.append(entry.getKey())
                    .append(" detected (last: ")
                    .append(entry.getValue() == null ? "Unknown" : entry.getValue())
                    .append(")");

            first = false;
        }

        if (result.isEmpty()) {
            return "detected";
        }

        return result.toString();
    }

    private static String detectionNoteWithCleared(
            Map<String, String> detections,
            String clearedDate
    ) {
        StringBuilder result = new StringBuilder(detectionNote(detections));

        if (!result.isEmpty() && !result.toString().equals("detected")) {
            result.append("\\n");
        } else if (result.toString().equals("detected")) {
            result.setLength(0);
        }

        result.append("cleared (last: ")
                .append(clearedDate)
                .append(")");

        return result.toString();
    }

    private static String parseLatestDate(String text) {
        if (text == null) return null;
        int start = text.lastIndexOf("(last:");
        if (start < 0) return null;
        int valueStart = start + "(last:".length();
        int end = text.indexOf(')', valueStart);
        if (end < 0) return null;
        String date = text.substring(valueStart, end).trim();
        return date.isBlank() ? null : date;
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

    public static int showOffenses(net.minecraft.commands.CommandSourceStack source, String identifier) {
        UUID playerUuid = resolvePlayerUuid(source, identifier);
        if (playerUuid == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("Player not found: " + identifier));
            return 0;
        }

        try {
            @SuppressWarnings("unchecked")
            List<Object> notes = (List<Object>) getNotesMethod.invoke(null, playerUuid);
            LinkedHashMap<String, String> offenses = new LinkedHashMap<>();
            for (Object note : findAssSystemNotes(notes)) {
                mergeDetectionDates(offenses, parseDetectionDates(noteText(note)));
            }

            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("PLAYER OFFENSES").withStyle(s -> s.withColor(0xFFAA00).withBold(true)), false);
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Player: " + playerName(source, playerUuid)), false);
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("UUID: " + playerUuid), false);

            if (offenses.isEmpty()) {
                source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("No Airport Security System detections recorded."), false);
            } else {
                for (Map.Entry<String, String> entry : offenses.entrySet()) {
                    source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                            entry.getKey() + " detected (last: " + (entry.getValue() == null ? "Unknown" : entry.getValue()) + ")"
                    ).withColor(0xFF5555), false);
                }
            }
            return 1;
        } catch (Throwable ex) {
            AirportSecuritySystem.LOGGER.warn("[Airport Security System] Failed to read offenses for {}.", playerUuid, unwrap(ex));
            source.sendFailure(net.minecraft.network.chat.Component.literal("Failed to read Airport Security System offenses."));
            return 0;
        }
    }

    public static int showOffenseRate(net.minecraft.commands.CommandSourceStack source) {
        if (!initialize()) {
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "Admin Notes integration is unavailable."));
            return 0;
        }

        try {
            Method getPlayersMethod = Class.forName(API_CLASS_NAME).getMethod("getPlayers");

            @SuppressWarnings("unchecked")
            List<UUID> playerUuids = (List<UUID>) getPlayersMethod.invoke(null);

            int totalPlayers = 0;
            int clearedPlayers = 0;
            int detectedPlayers = 0;
            Map<String, Integer> categoryCounts =
                    new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);

            for (UUID uuid : playerUuids) {
                Map<String, Object> state = getPlayerOffenseState(uuid);
                if (state == null) continue;

                totalPlayers++;
                if ("CLEAN".equals(state.get("status"))) {
                    clearedPlayers++;
                } else if ("DETECTED".equals(state.get("status"))) {
                    detectedPlayers++;

                    Object categories = state.get("categories");
                    if (categories instanceof Set<?> set) {
                        for (Object category : set) {
                            if (category instanceof String value && !value.isBlank()) {
                                categoryCounts.merge(value, 1, Integer::sum);
                            }
                        }
                    }
                }
            }

            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "───────────────────────────────────").withColor(0x555555), false);
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "AIRPORT SECURITY OFFENSE RATE")
                    .withStyle(st -> st.withColor(0xFFAA00).withBold(true)), false);
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "Players with ASS records: " + totalPlayers), false);
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "Cleared: " + clearedPlayers + " (" + percent(clearedPlayers, totalPlayers) + "%)"), false);
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "Detected: " + detectedPlayers + " (" + percent(detectedPlayers, totalPlayers) + "%)"), false);

            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(""), false);
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "Cheat type distribution among detected players:"), false);

            if (categoryCounts.isEmpty()) {
                source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                        "No current detections recorded."), false);
            } else {
                for (Map.Entry<String, Integer> entry : categoryCounts.entrySet()) {
                    int count = entry.getValue();
                    source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                            entry.getKey() + ": " + count + " player(s) — "
                                    + percent(count, detectedPlayers)
                                    + "% of detected players"
                    ).withColor(0xFF5555), false);
                }
            }

            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "───────────────────────────────────").withColor(0x555555), false);
            return 1;
        } catch (Throwable ex) {
            AirportSecuritySystem.LOGGER.warn(
                    "[Airport Security System] Failed to calculate offense rate.",
                    unwrap(ex));
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "Failed to read Airport Security System offense statistics."));
            return 0;
        }
    }

    private static UUID resolvePlayerUuid(net.minecraft.commands.CommandSourceStack source, String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        try {
            return UUID.fromString(identifier);
        } catch (IllegalArgumentException ignored) {
        }
        var server = source.getServer();
        if (server == null) return null;
        ServerPlayer online = server.getPlayerList().getPlayerByName(identifier);
        if (online != null) return online.getUUID();
        var cache = server.getProfileCache();
        if (cache != null) {
            try {
                var profile = cache.get(identifier);
                if (profile.isPresent()) return profile.get().getId();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String playerName(net.minecraft.commands.CommandSourceStack source, UUID uuid) {
        var server = source.getServer();
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) return online.getGameProfile().getName();
            var cache = server.getProfileCache();
            if (cache != null) {
                try {
                    var profile = cache.get(uuid);
                    if (profile.isPresent()) return profile.get().getName();
                } catch (Throwable ignored) {
                }
            }
        }
        return uuid.toString();
    }

    private static int percent(int value, int total) {
        return total <= 0 ? 0 : Math.round((value * 1000f) / total) / 10;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }

        return throwable;
    }
}
