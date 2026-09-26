package com.zaremate.airport_security_system;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.neoforged.neoforge.network.PacketDistributor;
import com.zaremate.airport_security_system.network.AirportSecuritySystemStatusPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import com.zaremate.airport_security_system.mixin.SignBlockEntityAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class AirportSecuritySystemEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LINES_PER_BATCH = 3;
    private static final String CTRL_KEYBIND = "key.forward";
    private static final Map<UUID, CheckSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PENDING_JOIN_CHECKS = new ConcurrentHashMap<>();
    private static final Set<UUID> FIRST_JOIN_CHECKED = ConcurrentHashMap.newKeySet();

    private AirportSecuritySystemEvents() {}

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        var command = Commands.literal("airport_security_system")
                .requires(source -> source.hasPermission(3) || LuckPermsPermissions.hasPermission(source, AirportSecuritySystemConfig.COMMAND_PERMISSION.get()))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> startCheckByIdentifier(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "player")
                        )));

        event.getDispatcher().register(command);
        event.getDispatcher().register(
                Commands.literal("ass")
                        .requires(source -> source.hasPermission(3) || LuckPermsPermissions.hasPermission(source, AirportSecuritySystemConfig.COMMAND_PERMISSION.get()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> startCheckByIdentifier(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")
                                )))
        );
    }

    /**
     * Resolves an online player by either their Minecraft name or UUID.
     *
     * <p>UUID input accepts both dashed and undashed forms.</p>
     */
    private static int startCheckByIdentifier(
            net.minecraft.commands.CommandSourceStack source,
            String identifier
    ) {
        ServerPlayer target = null;

        // First try the exact online player name.
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(identifier)) {
                target = player;
                break;
            }
        }

        // If it was not a name, try UUID.
        if (target == null) {
            try {
                String normalized = identifier.replace("-", "");
                if (normalized.length() == 32) {
                    normalized = normalized.replaceFirst(
                            "(?i)(.{8})(.{4})(.{4})(.{4})(.{12})",
                            "$1-$2-$3-$4-$5"
                    );
                }

                UUID uuid = UUID.fromString(normalized);
                target = source.getServer().getPlayerList().getPlayer(uuid);
            } catch (IllegalArgumentException ignored) {
                // Not a UUID; the name lookup above already failed.
            }
        }

        if (target == null) {
            source.sendFailure(Component.literal(
                    "Player not found or not online: " + identifier
            ));
            return 0;
        }

        return startCheck(target, source);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!AirportSecuritySystemConfig.AUTO_CHECK_ON_JOIN.get()) return;
        UUID uuid = player.getUUID();

        if (LuckPermsPermissions.hasPermission(player, AirportSecuritySystemConfig.JOIN_BYPASS_PERMISSION.get())) {
            LOGGER.info("[Airport Security System] Skipping automatic join check for {} due to LuckPerms permission '{}'.",
                    player.getGameProfile().getName(), AirportSecuritySystemConfig.JOIN_BYPASS_PERMISSION.get());
            sendClientStatus(player, AirportSecuritySystemStatusPayload.COMPLETE, 0, 0, 0, 0);
            return;
        }

        int joinChance = AirportSecuritySystemConfig.JOIN_CHECK_CHANCE_PERCENT.get();
        if (ThreadLocalRandom.current().nextInt(100) >= joinChance) {
            LOGGER.debug("[Airport Security System] {} was not selected for the automatic {}% join check.",
                    player.getGameProfile().getName(), joinChance);
            sendClientStatus(player, AirportSecuritySystemStatusPayload.COMPLETE, 0, 0, 0, 0);
            return;
        }

        if (AirportSecuritySystemConfig.ONLY_FIRST_JOIN.get() && !FIRST_JOIN_CHECKED.add(uuid)) {
            sendClientStatus(player, AirportSecuritySystemStatusPayload.COMPLETE, 0, 0, 0, 0);
            return;
        }

        List<AirportSecuritySystemProbe> joinProbes = AirportSecuritySystemConfig.blacklistedProbes();
        if (joinProbes.isEmpty()) {
            sendClientStatus(player, AirportSecuritySystemStatusPayload.COMPLETE, 0, 0, 0, 0);
            return;
        }

        sendClientStatus(player, AirportSecuritySystemStatusPayload.START, 0, joinProbes.size(), 0, 0);

        PENDING_JOIN_CHECKS.put(uuid,
                player.server.getTickCount() + AirportSecuritySystemConfig.JOIN_CHECK_DELAY_TICKS.get());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING_JOIN_CHECKS.remove(player.getUUID());
            CheckSession session = SESSIONS.remove(player.getUUID());
            if (session != null) finish(session, "logout");
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();

        for (var entry : new ArrayList<>(PENDING_JOIN_CHECKS.entrySet())) {
            if (tick < entry.getValue()) continue;
            UUID uuid = entry.getKey();
            PENDING_JOIN_CHECKS.remove(uuid);
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(uuid);
            if (player != null && player.isAlive() && !SESSIONS.containsKey(uuid)) {
                startCheck(player, null);
            }
        }

        for (CheckSession session : new ArrayList<>(SESSIONS.values())) {
            if (session.startTick > 0 && tick >= session.startTick) {
                session.startTick = 0;
                if (!session.finished)
                    sendBatch(session);
            }

            if (session.openTick > 0 && tick >= session.openTick) {
                session.openTick = 0;
                if (!session.finished && session.pos != null) {
                    session.player.connection.send(
                            new ClientboundOpenSignEditorPacket(session.pos, true));
                    // CheckHacks immediately clears the sign from the client's view
                    // after opening the editor; this does not touch the server world.
                    session.player.connection.send(
                            new ClientboundBlockUpdatePacket(
                                    session.pos, Blocks.AIR.defaultBlockState()));
                    session.awaiting = true;
                    session.timeoutTick = tick + AirportSecuritySystemConfig.TIMEOUT_TICKS.get();
                }
            }
            if (session.awaiting && tick >= session.timeoutTick)
                finish(session, "timeout");
        }
    }

    public static boolean isExpectedPacket(ServerPlayer player, ServerboundSignUpdatePacket packet) {
        CheckSession session = SESSIONS.get(player.getUUID());
        return session != null
                && session.awaiting
                && packet.isFrontText()
                && packet.getPos().equals(session.pos);
    }

    public static void handleSignResponse(ServerPlayer player, ServerboundSignUpdatePacket packet) {
        CheckSession session = SESSIONS.get(player.getUUID());
        if (!isExpectedPacket(player, packet)) return;

        session.awaiting = false;

        List<AirportSecuritySystemProbe> batch = session.probes.subList(
                session.index,
                Math.min(session.index + LINES_PER_BATCH, session.probes.size())
        );
        String[] lines = packet.getLines();

        String[] rawLines = Arrays.copyOf(lines, 4);
        for (int i = 0; i < rawLines.length; i++)
            if (rawLines[i] == null) rawLines[i] = "";

        String ctrlResponse = rawLines[3].trim();
        boolean exploitPreventer = ctrlResponse.equalsIgnoreCase(CTRL_KEYBIND);

        LOGGER.info(
                "[Airport Security System] Batch {} from {} L0='{}' L1='{}' L2='{}' CTRL='{}'{}",
                session.index / LINES_PER_BATCH,
                player.getGameProfile().getName(),
                rawLines[0].trim(),
                rawLines[1].trim(),
                rawLines[2].trim(),
                ctrlResponse,
                exploitPreventer ? " [ExploitPreventer detected]" : ""
        );

        for (int i = 0; i < batch.size(); i++) {
            String response = rawLines[i].trim();
            AirportSecuritySystemProbe probe = batch.get(i);

            ProbeResult result = evaluate(probe, response, exploitPreventer);

            LOGGER.info(
                    "[Airport Security System] {} -> {} (mode={}, response='{}')",
                    probe.key(),
                    result,
                    probe.mode(),
                    response
            );

            if (result == ProbeResult.DETECTED)
                session.detected.add(probe.key());
            else if (result == ProbeResult.PROTECTED)
                session.protectedKeys.add(probe.key());
        }

        restoreClientView(session);
        session.index += batch.size();

        if (session.index >= session.probes.size())
            finish(session, "complete");
        else
            sendBatch(session);
    }

    private static int startCheck(ServerPlayer target, net.minecraft.commands.CommandSourceStack commandSource) {
        if (SESSIONS.containsKey(target.getUUID())) {
            if (commandSource != null) commandSource.sendFailure(Component.literal("Airport Security System is already checking " + target.getGameProfile().getName() + "."));
            LOGGER.info("[Airport Security System] {} is already being checked.", target.getGameProfile().getName());
            return 0;
        }

        List<AirportSecuritySystemProbe> probes = AirportSecuritySystemConfig.blacklistedProbes();
        if (probes.isEmpty()) {
            if (commandSource != null) commandSource.sendFailure(Component.literal("No blacklisted keys are configured."));
            LOGGER.warn("[Airport Security System] No blacklisted keys are configured.");
            return 0;
        }

        CheckSession session = new CheckSession(target, commandSource, probes);
        SESSIONS.put(target.getUUID(), session);

        LOGGER.info("[Airport Security System] Checking {} for {} configured probe(s).",
                target.getGameProfile().getName(), probes.size());

        if (commandSource != null) {
            showAirportTitle(target);
            sendClientStatus(session, AirportSecuritySystemStatusPayload.START);
            session.startTick = target.server.getTickCount() + 40;
        } else {
            sendClientStatus(session, AirportSecuritySystemStatusPayload.START);
            sendBatch(session);
        }
        return 1;
    }

    private static void showAirportTitle(ServerPlayer target) {
        try {
            var source = target.createCommandSourceStack()
                    .withPermission(4);

            target.server.getCommands().performPrefixedCommand(
                    source,
                    "title @s times 0 40 0"
            );
            target.server.getCommands().performPrefixedCommand(
                    source,
                    "title @s title {\"text\":\"AIRPORT SECURITY\",\"color\":\"aqua\",\"bold\":true}"
            );
            target.server.getCommands().performPrefixedCommand(
                    source,
                    "title @s subtitle {\"text\":\"CHECK INCOMING\",\"color\":\"yellow\",\"bold\":true}"
            );
        } catch (Throwable ex) {
            LOGGER.warn("[Airport Security System] Failed to show airport title for {}.", target.getGameProfile().getName(), ex);
        }
    }

    private static void sendBatch(CheckSession session) {
        ServerPlayer player = session.player;
        BlockPos pos = findAir(player);

        if (pos == null) {
            finish(session, "no suitable client-only sign position");
            return;
        }

        session.pos = pos;
        session.originalState = player.serverLevel().getBlockState(pos);
        sendClientStatus(session, AirportSecuritySystemStatusPayload.PROGRESS);

        BlockState fakeSignState = Blocks.OAK_SIGN.defaultBlockState();
        SignBlockEntity sign = new SignBlockEntity(pos, fakeSignState);

        // Packet serialization in 1.21.1 needs a non-null Level for registry access.
        // This only attaches the detached object to the existing Level; it does not
        // add the block entity to the world or cause a world update.
        sign.setLevel(player.serverLevel());

        SignText text = new SignText();

        List<AirportSecuritySystemProbe> batch = session.probes.subList(
                session.index,
                Math.min(session.index + LINES_PER_BATCH, session.probes.size())
        );

        for (int i = 0; i < LINES_PER_BATCH; i++) {
            text = text.setMessage(
                    i,
                    i < batch.size() ? batch.get(i).component() : Component.empty()
            );
        }

        // CheckHacks reserves the fourth line for an ordinary keybind used
        // to identify clients that protect keybind translation.
        text = text.setMessage(3, Component.keybind(CTRL_KEYBIND));

        /*
         * Use the actual SignBlockEntity serializer so the NBT has the same
         * front_text/back_text structure as a normal Minecraft sign.
         */
        SignBlockEntityAccessor accessor = (SignBlockEntityAccessor) sign;
        accessor.airport_security_system$setFrontText(text);
        accessor.airport_security_system$setBackText(new SignText());
        accessor.airport_security_system$setEditor(player.getUUID());

        player.connection.send(new ClientboundBlockUpdatePacket(pos, fakeSignState));
        player.connection.send(ClientboundBlockEntityDataPacket.create(sign));

        // CheckHacks waits one tick after sending the sign data, then opens the
        // editor and immediately hides the sign from the checking client.
        session.openTick = player.server.getTickCount() + 1;
    }

    private enum ProbeResult { NOT_DETECTED, DETECTED, PROTECTED }

    private static ProbeResult evaluate(AirportSecuritySystemProbe probe, String response, boolean exploitPreventer) {
        if (response.isEmpty())
            return ProbeResult.NOT_DETECTED;

        String key = probe.key();
        if (response.length() == key.length() + 1
                && response.regionMatches(true, 0, key, 0, key.length())
                && Character.isLetter(response.charAt(key.length()))) {
            return ProbeResult.NOT_DETECTED;
        }

        return switch (probe.mode()) {
            case METEOR -> {
                if (response.equalsIgnoreCase(key))
                    yield ProbeResult.DETECTED;
                if (response.regionMatches(true, 0, probe.fallback(), 0, probe.fallback().length()))
                    yield ProbeResult.NOT_DETECTED;
                yield ProbeResult.DETECTED;
            }
            case TRANSLATE -> {
                if (response.regionMatches(true, 0, probe.fallback(), 0, probe.fallback().length()))
                    yield ProbeResult.NOT_DETECTED;
                if (response.equalsIgnoreCase(key))
                    yield ProbeResult.PROTECTED;
                yield ProbeResult.DETECTED;
            }
            case KEYBIND -> {
                if (exploitPreventer && response.equalsIgnoreCase(key))
                    yield ProbeResult.PROTECTED;
                if (response.equalsIgnoreCase(key))
                    yield ProbeResult.NOT_DETECTED;
                yield ProbeResult.DETECTED;
            }
        };
    }

    private static void finish(CheckSession session, String reason) {
        if (session.finished) return;

        session.finished = true;
        SESSIONS.remove(session.player.getUUID());
        restoreClientView(session);
        sendClientStatus(session,
                "complete".equals(reason)
                        ? AirportSecuritySystemStatusPayload.COMPLETE
                        : AirportSecuritySystemStatusPayload.FAILED);

        String name = session.player.getGameProfile().getName();

        if ("complete".equals(reason)) {
            if (!session.detected.isEmpty()) {
                StringBuilder details = new StringBuilder("Detected keybinds:\n")
                        .append(String.join("\n", session.detected));
                if (!session.protectedKeys.isEmpty()) {
                    details.append("\n\nProtected/probe-blocked:\n")
                            .append(String.join("\n", session.protectedKeys));
                }
                LOGGER.warn("[Airport Security System] {}: detected blacklisted keybinds:\n{}", name,
                        String.join("\n", session.detected));
                DiscordWebhook.send(
                        name,
                        session.player.getUUID().toString(),
                        "DETECTED",
                        details.toString(),
                        session.commandSource != null
                );
                sendCommandResult(session, "DETECTED", details.toString());
                broadcastStaff(session, (session.commandSource != null ? "[MANUAL] " : "") + name + " — DETECTED\n" + details);
                AdminNotesIntegration.recordCheckResult(session.player.getUUID(), session.detected);
            } else if (!session.protectedKeys.isEmpty()) {
                String list = String.join("\n", session.protectedKeys);
                LOGGER.info("[Airport Security System] {}: keybind probe protected for:\n{}", name, list);
                String details = "Protected/probe-blocked keybinds:\n" + list;
                DiscordWebhook.send(
                        name,
                        session.player.getUUID().toString(),
                        "INCONCLUSIVE",
                        details,
                        session.commandSource != null
                );
                sendCommandResult(session, "INCONCLUSIVE", details);
                broadcastStaff(session, (session.commandSource != null ? "[MANUAL] " : "") + name + " — INCONCLUSIVE\n" + details);
            } else {
                if (AirportSecuritySystemConfig.LOG_CLEAN_CHECKS.get())
                    LOGGER.info("[Airport Security System] {}: no blacklisted keybinds detected.", name);
                String details = "No configured blacklisted keybinds were resolved.";
                sendCommandResult(session, "CLEAN", details);
                AdminNotesIntegration.recordCheckResult(session.player.getUUID(), Set.of());
                // Clean checks are only logged/returned to the command sender; they are not broadcast.
                if (session.commandSource != null) {
                    DiscordWebhook.send(
                            name,
                            session.player.getUUID().toString(),
                            "CLEAN",
                            details,
                            true
                    );
                }
            }
        } else {
            LOGGER.info("[Airport Security System] {}: check ended ({})", name, reason);
            String details = "The client did not provide a usable response. Reason: " + reason;
            DiscordWebhook.send(
                    name,
                    session.player.getUUID().toString(),
                    "INCONCLUSIVE",
                    details,
                    session.commandSource != null
            );
            sendCommandResult(session, "INCONCLUSIVE", details);
            broadcastStaff(session, (session.commandSource != null ? "[MANUAL] " : "") + name + " — INCONCLUSIVE\n" + details);
        }
    }

    private static void sendClientStatus(CheckSession session, int state) {
        sendClientStatus(
                session.player,
                state,
                session.index,
                session.probes.size(),
                session.detected.size(),
                session.protectedKeys.size()
        );
    }

    private static void sendClientStatus(
            ServerPlayer player,
            int state,
            int completed,
            int total,
            int detected,
            int protectedCount
    ) {
        try {
            PacketDistributor.sendToPlayer(
                    player,
                    new AirportSecuritySystemStatusPayload(state, completed, total, detected, protectedCount)
            );
        } catch (Throwable ignored) {
            // The client overlay is optional; detection must continue without it.
        }
    }

    private static void sendCommandResult(CheckSession session, String status, String details) {
        if (session.commandSource == null) return;
        session.commandSource.sendSuccess(
                () -> Component.literal("Airport Security System: " + session.player.getGameProfile().getName()
                        + " — " + status + " — " + details),
                false
        );
    }

    private static void broadcastStaff(CheckSession session, String message) {
        Component component = Component.literal("[Airport Security System] " + message);
        for (ServerPlayer player : session.player.server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(3)
                    || LuckPermsPermissions.hasPermission(player, AirportSecuritySystemConfig.BROADCAST_PERMISSION.get())) {
                player.sendSystemMessage(component);
            }
        }
    }

    private static void restoreClientView(CheckSession session) {
        if (session.pos == null) return;

        // Restore only the checking client's local block view.
        session.player.connection.send(
                new ClientboundBlockUpdatePacket(session.pos, session.originalState)
        );

        session.pos = null;
    }

    private static BlockPos findAir(ServerPlayer player) {
        BlockPos base = player.blockPosition();

        for (int dy = 2; dy <= 6; dy++) {
            BlockPos pos = base.above(dy);
            if (player.serverLevel().getBlockState(pos).isAir())
                return pos;
        }

        return null;
    }

    static final class CheckSession {
        final ServerPlayer player;
        final net.minecraft.commands.CommandSourceStack commandSource;
        final List<AirportSecuritySystemProbe> probes;
        final Set<String> detected = new LinkedHashSet<>();
        final Set<String> protectedKeys = new LinkedHashSet<>();

        int index;
        int startTick;
        int openTick;
        int timeoutTick;
        BlockPos pos;
        BlockState originalState;
        boolean awaiting;
        boolean finished;

        CheckSession(ServerPlayer player, net.minecraft.commands.CommandSourceStack commandSource, List<AirportSecuritySystemProbe> probes) {
            this.player = player;
            this.commandSource = commandSource;
            this.probes = List.copyOf(probes);
        }
    }
}
