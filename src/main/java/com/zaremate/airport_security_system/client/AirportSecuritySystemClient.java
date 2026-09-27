package com.zaremate.airport_security_system.client;

import com.zaremate.airport_security_system.network.AirportSecuritySystemStatusPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = "airport_security_system", value = Dist.CLIENT)
public final class AirportSecuritySystemClient {
    private static volatile boolean active;
    private static volatile int state;
    private static volatile int completed;
    private static volatile int total;
    private static volatile int detected;
    private static volatile int protectedCount;
    private static volatile long hideAtNanos;
    private static volatile long airportUntilNanos;

    private AirportSecuritySystemClient() {}

    public static void handleStatus(AirportSecuritySystemStatusPayload payload) {
        active = payload.state() != AirportSecuritySystemStatusPayload.COMPLETE
                && payload.state() != AirportSecuritySystemStatusPayload.FAILED;
        state = payload.state();

        completed = payload.completed();
        total = payload.total();
        detected = payload.detected();
        protectedCount = payload.protectedCount();

        if (!active) {
            hideAtNanos = System.nanoTime() + 800_000_000L;
            closeBlockingScreen();
        }
    }

    private static void renderAirportAnnouncement(GuiGraphics graphics, Minecraft minecraft, int width, int height) {
        graphics.fill(0, 0, width, height, 0xE608111D);

        int boxWidth = Math.min(620, width - 40);
        int boxHeight = 220;
        int left = (width - boxWidth) / 2;
        int top = Math.max(30, height / 2 - boxHeight / 2);

        graphics.fill(left + 3, top + 3, left + boxWidth + 3, top + boxHeight + 3, 0x66000000);
        graphics.fill(left, top, left + boxWidth, top + boxHeight, 0xF0182230);
        graphics.fill(left, top, left + boxWidth, top + 4, 0xFF35C7FF);
        graphics.fill(left, top + 4, left + boxWidth, top + 8, 0xFFFFC857);

        String title = "AIRPORT SECURITY";
        int titleWidth = minecraft.font.width(title);
        graphics.drawString(
                minecraft.font,
                Component.literal(title),
                width / 2 - titleWidth / 2,
                top + 28,
                0xFFFFFFFF
        );

        String incoming = "CHECK INCOMING";
        graphics.drawString(
                minecraft.font,
                Component.literal(incoming),
                width / 2 - minecraft.font.width(incoming) / 2,
                top + 52,
                0xFFFFC857
        );

        graphics.drawString(
                minecraft.font,
                Component.literal("Please remain connected"),
                width / 2 - minecraft.font.width("Please remain connected") / 2,
                top + 84,
                0xFFE5EAF0
        );
        graphics.drawString(
                minecraft.font,
                Component.literal("Your client will be verified before entry"),
                width / 2 - minecraft.font.width("Your client will be verified before entry") / 2,
                top + 102,
                0xFF9FB0C2
        );

        int barLeft = left + 70;
        int barRight = left + boxWidth - 70;
        int barTop = top + 140;
        int barBottom = barTop + 12;
        graphics.fill(barLeft, barTop, barRight, barBottom, 0xFF2E3946);

        long remaining = Math.max(0L, airportUntilNanos - System.nanoTime());
        double progress = 1.0 - Math.min(1.0, remaining / 2_000_000_000.0);
        int fillRight = barLeft + (int) ((barRight - barLeft) * progress);
        if (fillRight > barLeft)
            graphics.fill(barLeft, barTop, fillRight, barBottom, 0xFF35C7FF);

        String seconds = String.format(java.util.Locale.ROOT, "%.1f s", remaining / 1_000_000_000.0);
        graphics.drawCenteredString(minecraft.font, seconds, width / 2, top + 162, 0xFFFFFFFF);
        graphics.drawCenteredString(minecraft.font, "BOARDING / VERIFICATION", width / 2, top + 190, 0xFF6DE0B8);
    }

    public static void reset() {
        active = false;
        state = 0;
        completed = 0;
        total = 0;
        detected = 0;
        protectedCount = 0;
        hideAtNanos = 0;
        airportUntilNanos = 0;
        closeBlockingScreen();
    }

    public static void tick() {
        if (!active) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        if (minecraft.screen instanceof AirportSecuritySystemLoadingScreen) return;

        minecraft.setScreen(new AirportSecuritySystemLoadingScreen());
    }

    public static void render(GuiGraphics graphics) {
        long now = System.nanoTime();
        if (!active && now >= hideAtNanos && now >= airportUntilNanos)
            return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null)
            return;

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        if (now < airportUntilNanos) {
            renderAirportAnnouncement(graphics, minecraft, width, height);
            return;
        }

        int boxWidth = Math.min(420, width - 40);
        int boxHeight = 112;
        int left = (width - boxWidth) / 2;
        int top = Math.max(24, height / 2 - boxHeight / 2);

        graphics.fill(left + 2, top + 2, left + boxWidth + 2, top + boxHeight + 2, 0x55000000);
        graphics.fill(left, top, left + boxWidth, top + boxHeight, 0xE8101010);
        graphics.fill(left, top, left + boxWidth, top + 2, 0xFF4FA3FF);

        graphics.drawCenteredString(
                minecraft.font,
                "Airport Security System",
                width / 2,
                top + 14,
                0xFFFFFFFF
        );

        String title = switch (state) {
            case AirportSecuritySystemStatusPayload.COMPLETE -> "Check complete";
            case AirportSecuritySystemStatusPayload.FAILED -> "Check could not be completed";
            default -> "Verifying your client";
        };

        graphics.drawCenteredString(
                minecraft.font,
                title,
                width / 2,
                top + 34,
                0xFFE0E0E0
        );

        if (total > 0) {
            int barLeft = left + 28;
            int barRight = left + boxWidth - 28;
            int barTop = top + 57;
            int barBottom = barTop + 10;
            int progress = Math.max(0, Math.min(total, completed));
            int fillRight = barLeft + (barRight - barLeft) * progress / total;

            graphics.fill(barLeft, barTop, barRight, barBottom, 0xFF303030);
            if (fillRight > barLeft)
                graphics.fill(barLeft, barTop, fillRight, barBottom, 0xFF4FA3FF);

            graphics.drawCenteredString(
                    minecraft.font,
                    completed + " / " + total + " probes",
                    width / 2,
                    top + 74,
                    0xFFBDBDBD
            );
        }

        if (detected > 0 || protectedCount > 0) {
            String result = "Findings: " + detected + " detected";
            if (protectedCount > 0)
                result += ", " + protectedCount + " protected";

            graphics.drawCenteredString(
                    minecraft.font,
                    result,
                    width / 2,
                    top + 93,
                    0xFFFFD166
            );
        } else {
            graphics.drawCenteredString(
                    minecraft.font,
                    "Please wait...",
                    width / 2,
                    top + 93,
                    0xFF8A8A8A
            );
        }
    }

    public static void beginEarlyLoading() {
        active = true;
        state = AirportSecuritySystemStatusPayload.START;
        completed = 0;
        total = 0;
        detected = 0;
        protectedCount = 0;
        hideAtNanos = 0;
        airportUntilNanos = System.nanoTime() + 2_000_000_000L;
    }

    private static void closeBlockingScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.screen instanceof AirportSecuritySystemLoadingScreen)
            minecraft.setScreen(null);
    }
}
