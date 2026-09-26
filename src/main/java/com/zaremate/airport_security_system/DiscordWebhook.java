package com.zaremate.airport_security_system;

import com.mojang.logging.LogUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;

public final class DiscordWebhook {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private DiscordWebhook() {}

    public static void send(String player, String uuid, String status, String details, boolean manual) {
        String url = AirportSecuritySystemConfig.webhookUrl();
        if (!AirportSecuritySystemConfig.WEBHOOK_ENABLED.get() || url.isBlank()) return;

        String safeStatus = escapeMarkdown(status);
        String description = "Player: **" + escapeMarkdown(player) + "**\n"
                + "UUID: `" + escapeMarkdown(uuid) + "`\n"
                + "Check: **" + (manual ? "MANUAL" : "AUTOMATIC") + "**\n"
                + "Status: **" + safeStatus + "**\n\n"
                + escapeMarkdown(details);

        String json = "{"
                + "\"username\":\"Airport Security System\","
                + "\"embeds\":[{"
                + "\"title\":\"Airport Security System result\","
                + "\"description\":\"" + escapeJson(description) + "\","
                + "\"color\":" + colorFor(status)
                + "}]"
                + "}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300)
                            LOGGER.warn("[Airport Security System] Discord webhook returned HTTP {}", response.statusCode());
                    })
                    .exceptionally(error -> {
                        LOGGER.warn("[Airport Security System] Discord webhook failed: {}", error.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            LOGGER.warn("[Airport Security System] Invalid Discord webhook URL: {}", e.getMessage());
        }
    }

    private static int colorFor(String status) {
        return switch (status.toUpperCase()) {
            case "DETECTED" -> 15158332;
            case "INCONCLUSIVE" -> 16776960;
            default -> 3066993;
        };
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String escapeMarkdown(String value) {
        return value.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`");
    }
}
