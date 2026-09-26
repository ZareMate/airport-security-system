package com.zaremate.airport_security_system;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public API for Airport Security System offense data.
 *
 * <p>Other server-side mods can use this API to read the authoritative ASS
 * offense database without depending on Admin Notes.</p>
 */
public final class AirportSecuritySystemAPI {
    private AirportSecuritySystemAPI() {}

    /**
     * Returns the current offense snapshot for a player.
     *
     * @return an immutable snapshot, or {@link Optional#empty()} when ASS has
     * no stored record for the player
     */
    public static Optional<PlayerOffense> getPlayerOffense(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }

        AirportSecuritySystemOffenses.PlayerRecord record =
                AirportSecuritySystemOffenses.getPlayer(playerUuid);

        if (record == null) {
            return Optional.empty();
        }

        return Optional.of(toApiRecord(playerUuid, record));
    }

    /**
     * Returns immutable snapshots for every player currently stored by ASS.
     */
    public static List<PlayerOffense> getPlayerOffenses() {
        List<PlayerOffense> result = new ArrayList<>();

        for (Map.Entry<UUID, AirportSecuritySystemOffenses.PlayerRecord> entry :
                collectPlayers().entrySet()) {
            result.add(toApiRecord(entry.getKey(), entry.getValue()));
        }

        return List.copyOf(result);
    }

    private static Map<UUID, AirportSecuritySystemOffenses.PlayerRecord> collectPlayers() {
        Map<UUID, AirportSecuritySystemOffenses.PlayerRecord> result =
                new LinkedHashMap<>();

        List<AirportSecuritySystemOffenses.PlayerRecord> records =
                AirportSecuritySystemOffenses.getPlayers();
        List<UUID> uuids = AirportSecuritySystemOffenses.getPlayerUuids();

        int count = Math.min(uuids.size(), records.size());
        for (int i = 0; i < count; i++) {
            result.put(uuids.get(i), records.get(i));
        }

        return result;
    }

    private static PlayerOffense toApiRecord(
            UUID playerUuid,
            AirportSecuritySystemOffenses.PlayerRecord record
    ) {
        return new PlayerOffense(
                playerUuid,
                record.getName(),
                record.getStatus(),
                record.getDetectionDates(),
                record.getClearedDate(),
                record.getTotalChecks(),
                record.getCleanChecks(),
                record.getDetectedChecks(),
                record.getInconclusiveChecks(),
                record.getDetectionCounts()
        );
    }

    /**
     * Immutable ASS offense snapshot exposed to other mods.
     *
     * @param playerUuid player UUID
     * @param playerName stored player name
     * @param status current ASS state: {@code CLEAN}, {@code DETECTED}, or
     *               {@code UNKNOWN}
     * @param detectionDates last-detected date by category
     * @param clearedDate last clean date, when present
     * @param totalChecks total completed checks
     * @param cleanChecks completed clean checks
     * @param detectedChecks completed detected checks
     * @param inconclusiveChecks completed inconclusive checks
     * @param detectionCounts historical detection count by category
     */
    public record PlayerOffense(
            UUID playerUuid,
            String playerName,
            String status,
            Map<String, String> detectionDates,
            String clearedDate,
            long totalChecks,
            long cleanChecks,
            long detectedChecks,
            long inconclusiveChecks,
            Map<String, Long> detectionCounts
    ) {
        public PlayerOffense {
            detectionDates = Map.copyOf(detectionDates);
            detectionCounts = Map.copyOf(detectionCounts);
        }
    }
}
