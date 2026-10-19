package com.kopite.devspace.github.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "github_oauth_states") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubOAuthState {
    @Id @Column(length = 64) private String stateHash;
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false, length = 64) private String sessionHash;
    @Column(nullable = false, columnDefinition = "text") private String encryptedVerifier;
    @Column(nullable = false) private Instant expiresAt;
    private Instant consumedAt;
    @Column(nullable = false) private long accountVersion;
    public static GitHubOAuthState create(String hash, UUID user, String sessionHash, String verifier, long accountVersion, Instant now) {
        var state = new GitHubOAuthState(); state.stateHash = hash; state.userId = user; state.sessionHash = sessionHash;
        state.encryptedVerifier = verifier; state.accountVersion = accountVersion; state.expiresAt = now.plusSeconds(600); return state;
    }
    public String consume(UUID user, String session, Instant now) {
        if (!userId.equals(user) || !sessionHash.equals(session) || consumedAt != null || !expiresAt.isAfter(now))
            throw new GitHubException("GITHUB_INVALID_STATE");
        consumedAt = now; return encryptedVerifier;
    }
    @Override public String toString() { return "GitHubOAuthState[redacted]"; }
}
