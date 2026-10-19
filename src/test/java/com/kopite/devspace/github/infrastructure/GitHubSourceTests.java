package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.GitHubInstallationTokens;
import com.kopite.devspace.github.domain.GitHubException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubSourceTests {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void sourceExcludesPullRequestsAndNormalizesNullBodyWithoutTruncatingOverflow() {
        var http = mock(GitHubHttpTransport.class); var tokens = mock(GitHubInstallationTokens.class);
        when(tokens.token(1,2)).thenReturn("installation-token");
        when(http.request(any(), eq("GET"), eq("installation-token"), isNull(), isNull())).thenReturn(json.readTree("""
                [{"id":3,"number":1,"title":"Task","body":null,"state":"open","updated_at":"2026-10-19T00:00:00Z","labels":[{"id":4}],"assignees":[]},
                 {"id":5,"number":2,"pull_request":{"url":"https://api.github.com/repos/o/r/pulls/2"}}]
                """));
        var source = new HttpGitHubSourceClient(new GitHubProperties(), http, tokens);
        var page = source.collect(1,2,"o/r","open",1,Instant.now());
        assertEquals(1, page.issues().size()); assertEquals("", page.issues().getFirst().body());
        assertEquals(List.of(4L), page.issues().getFirst().labelIds()); assertFalse(page.issues().getFirst().contentOverflow());
        var row = json.readTree("{\"id\":3,\"number\":1,\"title\":\"Title\",\"body\":" + json.writeValueAsString("😀".repeat(5001)) + ",\"state\":\"open\",\"updated_at\":\"2026-10-19T00:00:00Z\"}");
        var overflow = HttpGitHubSourceClient.issue(row);
        assertTrue(overflow.contentOverflow()); assertEquals("", overflow.body()); assertEquals(64, overflow.contentHash().length());
        when(http.request(any(), eq("GET"), eq("installation-token"), isNull(), isNull())).thenThrow(new GitHubException("GITHUB_ACCESS_DENIED", 404));
        assertEquals(List.of(1L), source.collect(1,2,"o/r","issue:1",1,Instant.now()).unavailableNumbers());
        when(http.request(any(), eq("GET"), eq("installation-token"), isNull(), isNull())).thenThrow(new GitHubException("GITHUB_UNAVAILABLE", 301));
        assertEquals(List.of(1L), source.collect(1,2,"o/r","issue:1",1,Instant.now()).unavailableNumbers());
    }
    @Test void sourceCursorBindsConnectionFiltersAndAccessScope() {
        var codec = new GitHubSourceCursor("test-only-key-at-least-32-bytes-long"); UUID link = UUID.randomUUID();
        String cursor = codec.encode(link,"1:1:1","open",42);
        assertEquals(42,codec.decode(link,"1:1:1","open",cursor));
        assertEquals("GITHUB_ACCESS_SCOPE_CHANGED", assertThrows(GitHubException.class, () -> codec.decode(link,"2:1:1","open",cursor)).code());
        assertThrows(GitHubException.class, () -> codec.decode(UUID.randomUUID(),"1:1:1","open",cursor));
        assertThrows(GitHubException.class, () -> codec.decode(link,"1:1:1","closed",cursor));
        assertThrows(GitHubException.class, () -> codec.decode(link,"1:1:1","open",cursor + "x"));
    }
    @Test void deliveryRecoveryUsesAppJwtAndOnlyExtractsCursorFromAllowedLinkHost() {
        var http = mock(GitHubHttpTransport.class); var jwt = mock(GitHubAppJwt.class); when(jwt.issue()).thenReturn("app-jwt");
        var client = new HttpGitHubDeliveryClient(new GitHubProperties(),http,jwt);
        when(http.responseApp(any(),eq("GET"),eq("app-jwt"),isNull(),isNull())).thenReturn(new GitHubHttpTransport.Response(json.readTree("[]"),
                java.net.http.HttpHeaders.of(Map.of("Link",List.of("<https://api.github.com/app/hook/deliveries?cursor=abc>; rel=\"next\"")),(a,b)->true)));
        assertEquals("abc",client.deliveries(null).nextCursor());
        client.redeliver(12);
        verify(http).requestApp(URI.create("https://api.github.com/app/hook/deliveries/12/attempts"),"POST","app-jwt",null,null);
        when(http.responseApp(any(),eq("GET"),eq("app-jwt"),isNull(),isNull())).thenReturn(new GitHubHttpTransport.Response(json.readTree("[]"),
                java.net.http.HttpHeaders.of(Map.of("Link",List.of("<https://evil.test/app/hook/deliveries?cursor=abc>; rel=\"next\"")),(a,b)->true)));
        assertThrows(GitHubException.class,()->client.deliveries(null));
    }
}
