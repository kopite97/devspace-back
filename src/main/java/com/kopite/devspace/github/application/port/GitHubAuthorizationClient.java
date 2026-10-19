package com.kopite.devspace.github.application.port;

import java.time.Instant;

public interface GitHubAuthorizationClient {
    Tokens exchange(String code, String verifier);
    Tokens refresh(String refreshToken);
    Identity identity(String accessToken);
    record Identity(long id, String login) {}
    // Deliberately override record rendering: credential values must never reach logs.
    record Tokens(String accessToken, String refreshToken, Instant expiresAt, Instant refreshExpiresAt) {
        @Override public String toString() { return "GitHubTokens[redacted]"; }
    }
}
