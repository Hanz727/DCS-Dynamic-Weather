package com.marlan.weatherupdate.service.destruction;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.marlan.weatherupdate.service.destruction.model.DestroyedReport;
import com.marlan.weatherupdate.service.destruction.model.WeaponImpact;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static java.lang.System.getenv;

/**
 * Client for the CVIC backend's battle-damage endpoints (localhost-only; this
 * program runs on the same box as the backend). Both endpoints scope
 * themselves: they return empty results unless the queried miz is the CURRENT
 * theatre's deployment mission, so no gating logic lives here.
 */
public class DestructionClient {
    /** Overridable for a non-default backend port (CVIC_BACKEND_URL). */
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8000";

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    public DestructionClient() {
        String env = getenv("CVIC_BACKEND_URL");
        this.baseUrl = (env == null || env.isEmpty()) ? DEFAULT_BASE_URL : env;
    }

    public DestroyedReport getDestroyed(String mizName) throws IOException, InterruptedException {
        return gson.fromJson(get("/api/v1/mission/destroyed", mizName), DestroyedReport.class);
    }

    public List<WeaponImpact> getImpacts(String mizName) throws IOException, InterruptedException {
        return gson.fromJson(get("/api/v1/mission/impacts", mizName),
                new TypeToken<List<WeaponImpact>>() {
                }.getType());
    }

    private String get(String path, String mizName) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + path + "?mission="
                + URLEncoder.encode(mizName, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("CVIC backend " + path + " returned " + response.statusCode()
                    + " for " + mizName);
        }
        return response.body();
    }
}
