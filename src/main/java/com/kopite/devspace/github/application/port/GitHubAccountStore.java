package com.kopite.devspace.github.application.port;

import com.kopite.devspace.github.domain.GitHubAccount;
import com.kopite.devspace.github.domain.GitHubOAuthState;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GitHubAccountStore {
    void saveState(GitHubOAuthState state, Instant now);
    AuthorizationProof consumeState(String hash, UUID user, String sessionHash, Instant now);
    Optional<GitHubAccount> account(UUID user);
    void authorize(UUID user, String stateHash, long expectedVersion, long githubId, String login, String access, String refresh, Instant expires, Instant refreshExpires, Instant now);
    RefreshClaim claimRefresh(UUID user, Instant now);
    boolean finishRefresh(UUID user, UUID claim, long version, String access, String refresh, Instant expires, Instant refreshExpires, Instant now);
    void failRefresh(UUID user, UUID claim, long version, Instant now);
    void cancelRefresh(UUID user, UUID claim, long version);
    boolean rejectCredential(UUID user, long version, Instant now);
    void disconnect(UUID user, Instant now);
    record AuthorizationProof(String encryptedVerifier, long accountVersion) {
        @Override public String toString() { return "AuthorizationProof[redacted]"; }
    }
    record RefreshClaim(UUID claim, long version, String encryptedRefreshToken, String encryptedAccessToken, boolean reauthorization) {
        @Override public String toString() { return "RefreshClaim[redacted]"; }
    }
}
