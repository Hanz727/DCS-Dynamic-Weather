package com.marlan.weatherupdate.service.destruction;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.marlan.shared.utilities.FileHandler;
import com.marlan.shared.utilities.Log;
import lombok.Data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Posts a battle-damage changelog entry + the updated .miz to its own Discord
 * webhook (separate from the METOC weather webhook) — mission editors see what
 * changed and get the file in one message. Only called when units/zones
 * actually changed; weather-only runs never reach this.
 *
 * Webhook URL lives in secrets\changelog_webhook.json (same convention as the
 * METOC webhook's discord_api_key.json); an absent/empty secret quietly skips.
 */
public class ChangelogDiscordPoster {
    private static final Log log = Log.getInstance();
    private static final String SECRET_PATH = "secrets\\changelog_webhook.json";
    // Discord's hard message cap is 2000 chars; leave room for the marker.
    private static final int MAX_CONTENT = 1900;

    private final String workingDir;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    @Data
    private static class ChangelogWebhook {
        private String changelogWebhook;
    }

    public ChangelogDiscordPoster(String workingDir) {
        this.workingDir = workingDir;
    }

    public void post(String changelogEntry, String mizPath) {
        String webhook = readWebhook();
        if (webhook == null || webhook.isEmpty()) {
            log.info("Battle damage: no changelog webhook configured (" + SECRET_PATH + "); skipping Discord post");
            return;
        }
        try {
            Path miz = Path.of(mizPath);
            byte[] mizBytes = Files.readAllBytes(miz);
            String boundary = "----CVICChangelog" + UUID.randomUUID();
            byte[] body = multipartBody(boundary, truncate(changelogEntry),
                    miz.getFileName().toString(), mizBytes);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhook))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                log.info("Battle-damage changelog posted to Discord (" + response.statusCode()
                        + "), miz attached: " + miz.getFileName());
            } else {
                log.error("Changelog Discord post failed (" + response.statusCode() + "): "
                        + response.body());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("Changelog Discord post interrupted");
        } catch (IOException | RuntimeException e) {
            log.error("Changelog Discord post failed: " + e.getMessage());
        }
    }

    private String readWebhook() {
        try {
            String secret = FileHandler.readFile(workingDir, SECRET_PATH);
            if (secret == null || secret.isEmpty()) return null;
            ChangelogWebhook parsed = gson.fromJson(secret, ChangelogWebhook.class);
            return parsed == null ? null : parsed.getChangelogWebhook();
        } catch (RuntimeException e) {
            log.error("Could not read " + SECRET_PATH + ": " + e.getMessage());
            return null;
        }
    }

    /** Discord message content, capped under the 2000-char hard limit. The
     *  full text is always in the changelog file the message can't replace. */
    String truncate(String entry) {
        String content = entry.strip();
        if (content.length() <= MAX_CONTENT) return content;
        return content.substring(0, MAX_CONTENT) + "\n... _(truncated - full changelog in repo)_";
    }

    /** payload_json part (message content) + files[0] part (the miz). */
    byte[] multipartBody(String boundary, String content, String fileName, byte[] fileBytes)
            throws IOException {
        String payloadJson = gson.toJson(Map.of("content", content));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + payloadJson + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
