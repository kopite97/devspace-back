package com.kopite.devspace.github.infrastructure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitHubConfigurationTests {
    @Test void browserRedirectAllowsOnlyHttpsOrLiteralLocalHttp() {
        for (String uri : new String[]{"https://example.test/settings", "http://localhost:8080/callback",
                "http://127.0.0.1:8080/callback", "http://[::1]:8080/callback"})
            assertDoesNotThrow(() -> GitHubProperties.requireBrowserEndpoint(uri));
        for (String uri : new String[]{"http://example.test/callback", "http://localhost.evil.test/callback",
                "http://127.1/callback", "http://2130706433/callback", "https://user@example.test/callback",
                "https://example.test/callback?next=bad", "https://example.test/#fragment", "file:///callback", "//localhost/callback"})
            assertThrows(IllegalStateException.class, () -> GitHubProperties.requireBrowserEndpoint(uri), uri);
    }
}
