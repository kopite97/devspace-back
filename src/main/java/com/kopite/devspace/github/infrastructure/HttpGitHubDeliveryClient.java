package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.GitHubDeliveryClient;
import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.stereotype.Component;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Component
public class HttpGitHubDeliveryClient implements GitHubDeliveryClient {
    private final GitHubProperties settings;
    private final GitHubHttpTransport http;
    private final GitHubAppJwt jwt;
    public HttpGitHubDeliveryClient(GitHubProperties settings, GitHubHttpTransport http, GitHubAppJwt jwt) {
        this.settings = settings; this.http = http; this.jwt = jwt;
    }
    @Override public Page deliveries(String cursor) {
        String path = settings.getApiUrl() + "/app/hook/deliveries?per_page=100";
        if (cursor != null) path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        var response = http.responseApp(URI.create(path), "GET", jwt.issue(), null, null);
        if (!response.body().isArray()) throw new GitHubException("GITHUB_UNAVAILABLE");
        List<Delivery> rows = new ArrayList<>();
        try {
            for (var row : response.body()) rows.add(new Delivery(row.path("id").asLong(), UUID.fromString(row.path("guid").asString()),
                    Instant.parse(row.path("delivered_at").asString()), row.path("status_code").asInt(), row.path("event").asString(),
                    row.hasNonNull("installation_id") ? row.path("installation_id").asLong() : null,
                    row.hasNonNull("repository_id") ? row.path("repository_id").asLong() : null));
            return new Page(rows, next(response.headers().firstValue("Link").orElse("")));
        } catch (RuntimeException ex) { throw new GitHubException("GITHUB_UNAVAILABLE"); }
    }
    @Override public void redeliver(long delivery) {
        if (delivery <= 0) throw new GitHubException("VALIDATION_ERROR");
        http.requestApp(URI.create(settings.getApiUrl() + "/app/hook/deliveries/" + delivery + "/attempts"), "POST", jwt.issue(), null, null);
    }
    private String next(String header) {
        for (String part : header.split(",")) {
            if (!part.contains("rel=\"next\"")) continue;
            int start = part.indexOf('<'), end = part.indexOf('>'); if (start < 0 || end <= start) throw new IllegalArgumentException();
            URI uri = URI.create(part.substring(start + 1, end)); URI base = URI.create(settings.getApiUrl());
            if (!Objects.equals(uri.getScheme(), base.getScheme()) || !Objects.equals(uri.getHost(), base.getHost())
                    || uri.getUserInfo() != null || !uri.getPath().equals("/app/hook/deliveries")) throw new IllegalArgumentException();
            for (String pair : uri.getRawQuery().split("&")) if (pair.startsWith("cursor=")) {
                String cursor = URLDecoder.decode(pair.substring(7), StandardCharsets.UTF_8);
                if (cursor.length() > 2048) throw new IllegalArgumentException(); return cursor;
            }
            throw new IllegalArgumentException();
        }
        return null;
    }
}
