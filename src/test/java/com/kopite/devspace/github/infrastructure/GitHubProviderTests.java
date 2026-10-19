package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.domain.GitHubException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubProviderTests {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void userRepositoryProofRequiresUnsuspendedInstallationIssuesPermissionAndSelectedRepository() {
        var http = mock(GitHubHttpTransport.class); var props = new GitHubProperties();
        var adapter = new HttpGitHubRepositoryClient(props, http);
        URI installations = URI.create("https://api.github.com/user/installations?per_page=100&page=1");
        URI repos = URI.create("https://api.github.com/user/installations/12/repositories?per_page=100&page=1");
        when(http.request(installations, "GET", "user", null, null)).thenReturn(json.readTree("""
                {"total_count":1,"installations":[{"id":12,"account":{"login":"org","type":"Organization"},"suspended_at":null,"permissions":{"issues":"read"}}]}
                """));
        when(http.request(repos, "GET", "user", null, null)).thenReturn(json.readTree("""
                {"total_count":1,"repositories":[{"id":34,"full_name":"org/private","html_url":"https://github.com/org/private","private":true,"has_issues":true}]}
                """));
        assertTrue(adapter.verify("user", 12, 34).privateRepository());
        assertEquals("GITHUB_ACCESS_DENIED", assertThrows(GitHubException.class, () -> adapter.verify("user", 12, 35)).code());
        assertEquals("GITHUB_ACCESS_DENIED", assertThrows(GitHubException.class, () -> adapter.verify("user", 13, 34)).code());
        when(http.request(installations, "GET", "user", null, null)).thenReturn(json.readTree("""
                {"total_count":1,"installations":[{"id":12,"account":{"login":"org","type":"Organization"},"suspended_at":"2026-10-19T00:00:00Z","permissions":{"issues":"read"}}]}
                """));
        assertEquals("GITHUB_ACCESS_DENIED", assertThrows(GitHubException.class, () -> adapter.verify("user", 12, 34)).code());
    }
    @Test void installationTokenUsesAppJwtAndIsRepositoryScopedCachedAndInvalidatable() {
        var http = mock(GitHubHttpTransport.class); var jwt = mock(GitHubAppJwt.class);
        when(jwt.issue()).thenReturn("app-jwt");
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
        var adapter = new HttpGitHubInstallationTokens(new GitHubProperties(), http, jwt, clock);
        String body = "{\"repository_ids\":[34],\"permissions\":{\"issues\":\"read\",\"metadata\":\"read\"}}";
        URI uri = URI.create("https://api.github.com/app/installations/12/access_tokens");
        when(http.requestApp(uri, "POST", "app-jwt", "application/json", body))
                .thenReturn(json.readTree("{\"token\":\"installation-secret\",\"expires_at\":\"2026-10-19T01:00:00Z\"}"));
        assertEquals("installation-secret", adapter.token(12, 34));
        assertEquals("installation-secret", adapter.token(12, 34));
        verify(http, times(1)).requestApp(uri, "POST", "app-jwt", "application/json", body);
        adapter.invalidate(12); adapter.token(12, 34);
        verify(http, times(2)).requestApp(uri, "POST", "app-jwt", "application/json", body);
    }
}
