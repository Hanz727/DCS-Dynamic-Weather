package com.marlan.weatherupdate.service.airplanclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
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
    // uvicorn rejects ("Invalid HTTP request received"), degrading the request.
    HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public AirplanClient() {

    }

    public String getNextEvtTime() {
        try {
            HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(new URI("http://127.0.0.1:8000/api/v1/airplan/next-event-time"))
                    .build();
            HttpResponse<String> response = sendRequest(getRequest);

            if (response == null || response.body() == null) {
                log.error("No response from airplan API");
                return null;
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Airplan API returned status " + response.statusCode());
                return null;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            JsonElement takeoffTime = json.get("takeoff_time");
            if (takeoffTime == null || takeoffTime.isJsonNull()) {
                return null;
            }

            return takeoffTime.getAsString();
        } catch (URISyntaxException use) {
            log.error("Invalid URI: " + use.getMessage());
        } catch (JsonSyntaxException jse) {
            log.error("Failed to parse JSON response: " + jse.getMessage());
        } catch (Exception e) {
            log.error("Error fetching next event time: " + e.getMessage());
        }
        return null;
    }

    private HttpResponse<String> sendRequest(HttpRequest getRequest) {
        try {
            return this.httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(e.getMessage());
        }
        return null;
    }
}
