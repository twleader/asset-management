package com.steven.assets.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link GoogleHttpClient} 的正式實作（Requirement 102 / Task 366）：一律用
 * {@code java.net.http.HttpClient}（{@code HttpClient.newHttpClient()}，逾時
 * {@code Duration.ofSeconds(15)}），不引入任何 Google API client library 或 OkHttp。
 */
@Component
public class JdkGoogleHttpClient implements GoogleHttpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public Response post(String url, String contentType, String body, String bearerToken)
            throws IOException, InterruptedException {
        return send("POST", url, contentType, body, bearerToken);
    }

    @Override
    public Response get(String url, String bearerToken) throws IOException, InterruptedException {
        return send("GET", url, null, null, bearerToken);
    }

    @Override
    public Response put(String url, String contentType, String body, String bearerToken)
            throws IOException, InterruptedException {
        return send("PUT", url, contentType, body, bearerToken);
    }

    private Response send(String method, String url, String contentType, String body, String bearerToken)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        builder.method(method, publisher);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.body());
    }
}
