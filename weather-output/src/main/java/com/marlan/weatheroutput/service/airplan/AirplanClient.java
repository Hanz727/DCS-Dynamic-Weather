package com.marlan.weatheroutput.service.airplan;

import com.marlan.shared.utilities.Log;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class AirplanClient {
    private static final Log log = Log.getInstance();
    // Pin to HTTP/1.1: the default HTTP/2 client sends h2c upgrade headers that
    // uvicorn rejects ("Invalid HTTP request received"), which drops the
    // Content-Type and makes the API 422 the body. Old Flask tolerated it.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public void postMetar(String metar) {
        try {
            String json = "{\"metar\":\"" + metar.replace("\"", "\\\"") + "\"}";
            HttpRequest putRequest = HttpRequest.newBuilder()
                    .uri(new URI("http://localhost:8000/api/v1/weather/metar"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(putRequest, HttpResponse.BodyHandlers.ofString());
            log.info("Weather METAR API Response: " + response);
        } catch (URISyntaxException use) {
            log.error(use.getMessage());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(e.getMessage());
        }
    }
}
