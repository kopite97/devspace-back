package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.GitHubInstallationTokens;
import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.*;
import java.util.*;

@Component
public class HttpGitHubInstallationTokens implements GitHubInstallationTokens {
    private final GitHubProperties settings;
    private final GitHubHttpTransport http;
    private final GitHubAppJwt jwt;
    private final Clock clock;
    // Process-local bounded cache. Nothing is persisted; an expired/lost token is safely reissued.
    private final Map<Key, Token> cache = new LinkedHashMap<>();
    public HttpGitHubInstallationTokens(GitHubProperties settings, GitHubHttpTransport http, GitHubAppJwt jwt, Clock projectClock) {
        this.settings = settings; this.http = http; this.jwt = jwt; this.clock = projectClock;
    }
    @Override public synchronized String token(long installation, long repository) {
        if (installation <= 0 || repository <= 0) throw new GitHubException("VALIDATION_ERROR");
        Instant now = clock.instant();
        cache.values().removeIf(t -> !t.expires().isAfter(now.plusSeconds(60)));
        var key = new Key(installation, repository); var current = cache.get(key);
        if (current != null) return current.value();
        var response = http.requestApp(URI.create(settings.getApiUrl() + "/app/installations/" + installation + "/access_tokens"),
                "POST", jwt.issue(), "application/json", "{\"repository_ids\":[" + repository + "],\"permissions\":{\"issues\":\"read\",\"metadata\":\"read\"}}");
        try {
            String value = response.path("token").asString(""); Instant expires = Instant.parse(response.path("expires_at").asString(""));
            if (value.isBlank() || !expires.isAfter(now.plusSeconds(60))) throw new IllegalArgumentException();
            if (cache.size() >= 128) cache.remove(cache.keySet().iterator().next());
            cache.put(key, new Token(value, expires)); return value;
        } catch (RuntimeException ex) { throw new GitHubException("GITHUB_INVALID_AUTHORIZATION"); }
    }
    @Override public synchronized void invalidate(long installation) { cache.keySet().removeIf(key -> key.installation() == installation); }
    private record Key(long installation, long repository) {}
    private record Token(String value, Instant expires) {
        @Override public String toString() { return "InstallationToken[redacted]"; }
    }
}
