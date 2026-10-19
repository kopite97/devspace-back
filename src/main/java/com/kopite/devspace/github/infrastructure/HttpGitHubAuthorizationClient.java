package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.GitHubAuthorizationClient;
import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import tools.jackson.databind.JsonNode;

@Component
public class HttpGitHubAuthorizationClient implements GitHubAuthorizationClient {
    private final GitHubHttpTransport http;
    private final GitHubProperties settings;
    private final Clock clock;
    public HttpGitHubAuthorizationClient(GitHubHttpTransport http, GitHubProperties settings, Clock projectClock) {
        this.http = http; this.settings = settings; this.clock = projectClock;
    }
    @Override public Tokens exchange(String code, String verifier) {
        return tokens("code=" + encode(code) + "&redirect_uri=" + encode(settings.getCallbackUrl()) + "&code_verifier=" + encode(verifier));
    }
    @Override public Tokens refresh(String refreshToken) {
        return tokens("grant_type=refresh_token&refresh_token=" + encode(refreshToken));
    }
    private Tokens tokens(String fields) {
        JsonNode value = http.request(URI.create(settings.getTokenUrl()), "POST", null, "application/x-www-form-urlencoded",
                "client_id=" + encode(settings.getClientId()) + "&client_secret=" + encode(settings.getClientSecret()) + "&" + fields);
        if (value.has("error") || !value.hasNonNull("access_token") || !value.hasNonNull("refresh_token"))
            throw new GitHubException("GITHUB_REAUTHORIZATION_REQUIRED");
        long lifetime = value.path("expires_in").asLong(0), refreshLifetime = value.path("refresh_token_expires_in").asLong(0);
        if (lifetime <= 0 || refreshLifetime <= 0) throw new GitHubException("GITHUB_REAUTHORIZATION_REQUIRED");
        return new Tokens(value.get("access_token").asString(), value.get("refresh_token").asString(),
                clock.instant().plusSeconds(lifetime), clock.instant().plusSeconds(refreshLifetime));
    }
    @Override public Identity identity(String accessToken) {
        var value = http.request(URI.create(settings.getApiUrl() + "/user"), "GET", accessToken, null, null);
        long id = value.path("id").asLong(0); String login = value.path("login").asString("");
        if (id <= 0 || login.isBlank()) throw new GitHubException("GITHUB_UNAVAILABLE");
        return new Identity(id, login);
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
