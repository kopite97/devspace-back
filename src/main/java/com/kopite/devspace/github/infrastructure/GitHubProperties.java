package com.kopite.devspace.github.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "app.github")
@Getter
public class GitHubProperties {
    @Setter private boolean enabled;
    @Setter private String appId = "";
    @Setter private String clientId = "";
    @Setter private String clientSecret = "";
    @Setter private String appSlug = "";
    @Setter private String privateKeyPath = "";
    @Setter private String encryptionKey = "";
    @Setter private String encryptionKeyId = "v1";
    @Setter private String webhookSecret = "";
    @Setter private String callbackUrl = "";
    @Setter private String frontendReturnUrl = "";
    @Setter private String apiUrl = "https://api.github.com";
    @Setter private String authorizationUrl = "https://github.com/login/oauth/authorize";
    @Setter private String tokenUrl = "https://github.com/login/oauth/access_token";
    @Setter private String apiVersion = "2026-03-10";

    @PostConstruct
    void validate() {
        if (!enabled) return;
        if (!appId.matches("[1-9][0-9]*") || clientId.isBlank() || clientSecret.isBlank() || !appSlug.matches("[A-Za-z0-9-]+")
                || webhookSecret.length() < 32 || privateKeyPath.isBlank())
            throw new IllegalStateException("GitHub enabled configuration is incomplete");
        requireHttps(apiUrl, "api.github.com");
        requireHttps(authorizationUrl, "github.com");
        requireHttps(tokenUrl, "github.com");
        requireBrowserEndpoint(callbackUrl);
        requireBrowserEndpoint(frontendReturnUrl);
        if (!encryptionKeyId.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalStateException("Invalid GitHub encryption key ID");
        try {
            if (java.util.Base64.getDecoder().decode(encryptionKey).length != 32)
                throw new IllegalArgumentException();
            if (!java.nio.file.Files.isRegularFile(Path.of(privateKeyPath)))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("GitHub key configuration is invalid");
        }
    }

    private static void requireHttps(String value, String host) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null
                    || (host != null && !host.equals(uri.getHost()))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid GitHub endpoint configuration");
        }
    }

    static void requireBrowserEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
            if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback)))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid GitHub browser endpoint configuration");
        }
    }

    @Override public String toString() { return "GitHubProperties[redacted]"; }
}
