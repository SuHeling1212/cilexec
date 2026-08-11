package com.follarce.health;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthServerTest {
    @Test
    void returnsJsonInsteadOfDroppingConnectionsForAnInstantTimestamp() throws Exception {
        HealthState state = new HealthState();
        try (HealthServer server = new HealthServer(0, state)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/health/ready"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("\"startedAt\":"), response.body());
            assertTrue(response.body().contains("\"ready\":false"), response.body());
            assertTrue(response.body().contains("\"effectWorkers\":false"), response.body());
            assertTrue(response.body().contains("\"workListener\":false"), response.body());
            assertTrue(response.body().contains("\"databaseCheckedAt\":null"), response.body());
        }
    }
}
