package dev.overseersmp.overseer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Posts a message to a Discord webhook. The URL is a secret: it is never logged, and failures report the status code only. */
public final class DiscordWebhook {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Gson GSON = new Gson();

    private DiscordWebhook() {}

    public static boolean looksValid(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI u = URI.create(url);
            return "https".equals(u.getScheme()) && u.getHost() != null
                    && (u.getHost().equals("discord.com") || u.getHost().endsWith(".discord.com") || u.getHost().equals("discordapp.com"))
                    && u.getPath() != null && u.getPath().startsWith("/api/webhooks/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** @return HTTP status (204/200 = delivered); fails with a message that contains no URL */
    public static CompletableFuture<Integer> post(String url, String content) {
        if (!looksValid(url)) return CompletableFuture.failedFuture(new IllegalStateException("webhook not configured or invalid"));
        JsonObject o = new JsonObject();
        o.addProperty("username", "The Overseer");
        o.addProperty("content", content.length() > 1900 ? content.substring(0, 1900) : content);
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());          // never ping anyone
        o.add("allowed_mentions", allowed);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(o))).build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .handle((r, err) -> {
                    if (err != null) throw new IllegalStateException("webhook request failed: " + err.getClass().getSimpleName());
                    if (r.statusCode() >= 300) throw new IllegalStateException("webhook HTTP " + r.statusCode());
                    return r.statusCode();
                });
    }
}
