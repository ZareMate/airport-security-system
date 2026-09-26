package com.zaremate.airport_security_system;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent Airport Security System offense storage.
 *
 * <p>This is the authoritative data source for {@code /offenses} and
 * {@code /offense_rate}. Admin Notes integration may mirror the timeline,
 * but ASS commands never depend on Admin Notes.</p>
 */
public final class AirportSecuritySystemOffenses {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final Map<UUID, PlayerRecord> PLAYERS =
            new LinkedHashMap<>();

    private static Path dataFile;

    private AirportSecuritySystemOffenses() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        initialize(event.getServer());
    }

    public static synchronized void initialize(MinecraftServer server) {
        if (server == null) {
            return;
        }

        dataFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("airport_security_system_offenses.json");

        load();
    }

    /**
     * Records a completed check.
     *
     * <p>DETECTED and CLEAN update the persistent offense state.
     * INCONCLUSIVE only increments the check counter and does not change the
     * offense timeline.</p>
     */
    public static synchronized void recordCheck(
            UUID playerUuid,
            String playerName,
            String status,
            Set<String> detectedKeys
    ) {
        if (playerUuid == null) {
            return;
        }

        ensureInitialized();

        PlayerRecord record = PLAYERS.computeIfAbsent(
                playerUuid,
                ignored -> new PlayerRecord(playerName)
        );

        if (playerName != null && !playerName.isBlank()) {
            record.name = playerName;
        }

        String normalizedStatus = status == null
                ? "INCONCLUSIVE"
                : status.trim().toUpperCase(Locale.ROOT);

        record.totalChecks++;

        switch (normalizedStatus) {
            case "DETECTED" -> {
                record.detectedChecks++;

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
                    String today = LocalDate.now().format(DATE_FORMAT);

                    for (String category : categories) {
                        record.detectionDates.put(category, today);
                        record.detectionCounts.merge(category, 1L, Long::sum);
                    }

                    // A later detection makes the current state detected again.
                    record.clearedDate = null;
                }
            }

            case "CLEAN" -> {
                record.cleanChecks++;
                record.clearedDate = LocalDate.now().format(DATE_FORMAT);
            }

            default -> record.inconclusiveChecks++;
        }

        save();
    }

    public static synchronized PlayerRecord getPlayer(UUID playerUuid) {
        ensureInitialized();
        PlayerRecord record = PLAYERS.get(playerUuid);
        return record == null ? null : record.copy();
    }

    public static synchronized List<UUID> getPlayerUuids() {
        ensureInitialized();
        return List.copyOf(PLAYERS.keySet());
    }

    public static synchronized List<PlayerRecord> getPlayers() {
        ensureInitialized();

        List<PlayerRecord> result = new ArrayList<>();
        for (PlayerRecord record : PLAYERS.values()) {
            result.add(record.copy());
        }
        return List.copyOf(result);
    }

    public static synchronized UUID resolvePlayer(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }

        ensureInitialized();

        String value = identifier.trim();

        try {
            UUID uuid = UUID.fromString(value);
            if (PLAYERS.containsKey(uuid)) {
                return uuid;
            }
            return null;
        } catch (IllegalArgumentException ignored) {
        }

        for (Map.Entry<UUID, PlayerRecord> entry : PLAYERS.entrySet()) {
            PlayerRecord record = entry.getValue();
            if (record.name != null && record.name.equalsIgnoreCase(value)) {
                return entry.getKey();
            }
        }

        return null;
    }

    private static void ensureInitialized() {
        if (dataFile != null) {
            return;
        }

        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            initialize(server);
        }
    }

    private static void load() {
        PLAYERS.clear();

        if (dataFile == null) {
            return;
        }

        if (Files.notExists(dataFile)) {
            save();
            return;
        }

        try {
            String json = Files.readString(dataFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            JsonElement playersElement = root.get("players");
            if (playersElement == null || !playersElement.isJsonObject()) {
                return;
            }

            for (Map.Entry<String, JsonElement> entry :
                    playersElement.getAsJsonObject().entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    PlayerRecord record = GSON.fromJson(
                            entry.getValue(),
                            PlayerRecord.class
                    );

                    if (record != null) {
                        record.normalize();
                        PLAYERS.put(uuid, record);
                    }
                } catch (Exception ex) {
                    AirportSecuritySystem.LOGGER.warn(
                            "[Airport Security System] Skipping malformed offense record for '{}'.",
                            entry.getKey(),
                            ex
                    );
                }
            }

            save();
        } catch (Exception ex) {
            AirportSecuritySystem.LOGGER.error(
                    "[Airport Security System] Failed to load offense storage.",
                    ex
            );
        }
    }

    private static void save() {
        if (dataFile == null) {
            return;
        }

        try {
            Storage storage = new Storage();
            storage.version = 1;

            for (Map.Entry<UUID, PlayerRecord> entry : PLAYERS.entrySet()) {
                storage.players.put(entry.getKey().toString(), entry.getValue());
            }

            Path parent = dataFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(
                    dataFile,
                    GSON.toJson(storage),
                    StandardCharsets.UTF_8
            );
        } catch (IOException ex) {
            AirportSecuritySystem.LOGGER.error(
                    "[Airport Security System] Failed to save offense storage.",
                    ex
            );
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

    public static final class PlayerRecord {
        private String name;
        private Map<String, String> detectionDates = new LinkedHashMap<>();
        private String clearedDate;
        private long totalChecks;
        private long cleanChecks;
        private long detectedChecks;
        private long inconclusiveChecks;
        private Map<String, Long> detectionCounts = new LinkedHashMap<>();

        public PlayerRecord() {
        }

        private PlayerRecord(String name) {
            this.name = name;
        }

        public PlayerRecord copy() {
            PlayerRecord copy = new PlayerRecord(name);
            copy.detectionDates = new LinkedHashMap<>(detectionDates);
            copy.clearedDate = clearedDate;
            copy.totalChecks = totalChecks;
            copy.cleanChecks = cleanChecks;
            copy.detectedChecks = detectedChecks;
            copy.inconclusiveChecks = inconclusiveChecks;
            copy.detectionCounts = new LinkedHashMap<>(detectionCounts);
            return copy;
        }

        private void normalize() {
            if (detectionDates == null) {
                detectionDates = new LinkedHashMap<>();
            }
            if (detectionCounts == null) {
                detectionCounts = new LinkedHashMap<>();
            }
            if (name == null) {
                name = "";
            }
        }

        public String getName() {
            return name;
        }

        public Map<String, String> getDetectionDates() {
            return Map.copyOf(detectionDates);
        }

        public String getClearedDate() {
            return clearedDate;
        }

        public long getTotalChecks() {
            return totalChecks;
        }

        public long getCleanChecks() {
            return cleanChecks;
        }

        public long getDetectedChecks() {
            return detectedChecks;
        }

        public long getInconclusiveChecks() {
            return inconclusiveChecks;
        }

        public Map<String, Long> getDetectionCounts() {
            return Map.copyOf(detectionCounts);
        }

        public String getStatus() {
            if (clearedDate != null) {
                return "CLEAN";
            }

            if (!detectionDates.isEmpty()) {
                return "DETECTED";
            }

            return "UNKNOWN";
        }
    }

    private static final class Storage {
        int version = 1;
        Map<String, PlayerRecord> players = new LinkedHashMap<>();
    }
}
