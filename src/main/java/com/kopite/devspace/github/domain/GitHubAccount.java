package com.kopite.devspace.github.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "github_accounts") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubAccount {
    @Id private UUID userId;
    @Column(nullable = false) private long githubUserId;
    @Column(nullable = false, columnDefinition = "text") private String login;
    @Column(columnDefinition = "text") private String encryptedAccessToken;
    @Column(columnDefinition = "text") private String encryptedRefreshToken;
    private Instant expiresAt;
    private Instant refreshExpiresAt;
    @Column(nullable = false, columnDefinition = "text") private String status;
    @Column(nullable = false) private long tokenVersion;
    @Column(nullable = false) private long accessVersion = 1;
    private UUID refreshClaim;
    private Instant refreshClaimUntil;
    @Column(nullable = false) private Instant updatedAt;

    public static GitHubAccount connect(UUID owner, long githubId, String login, String access, String refresh,
                                        Instant expires, Instant refreshExpires, Instant now) {
        var account = new GitHubAccount(); account.userId = owner; account.githubUserId = githubId;
        account.authorize(githubId, login, access, refresh, expires, refreshExpires, now); return account;
    }
    public void authorize(long githubId, String name, String access, String refresh, Instant expires, Instant refreshExpires, Instant now) {
        if (githubId != githubUserId && !"disconnected".equals(status)) throw new GitHubException("GITHUB_ACCOUNT_ALREADY_CONNECTED");
        if (githubId <= 0 || name == null || name.isBlank() || access == null || refresh == null || !expires.isAfter(now))
            throw new GitHubException("GITHUB_INVALID_AUTHORIZATION");
        githubUserId = githubId; login = name; encryptedAccessToken = access; encryptedRefreshToken = refresh;
        expiresAt = expires; refreshExpiresAt = refreshExpires; status = "connected";
        tokenVersion = Math.incrementExact(tokenVersion); refreshClaim = null; refreshClaimUntil = null; updatedAt = now;
    }
    public UUID claimRefresh(Instant now) {
        if (!"connected".equals(status) || !refreshExpiresAt.isAfter(now)) { requireReauthorization(now); return null; }
        if (refreshClaim != null) {
            if (refreshClaimUntil.isAfter(now)) throw new GitHubException("GITHUB_REFRESH_IN_PROGRESS");
            // We cannot know whether the dead worker consumed the refresh token.
            requireReauthorization(now); return null;
        }
        refreshClaim = UUID.randomUUID(); refreshClaimUntil = now.plusSeconds(60); return refreshClaim;
    }
    public boolean ownsRefresh(UUID claim, long version) { return tokenVersion == version && claim != null && claim.equals(refreshClaim); }
    public void cancelRefresh(UUID claim, long version) {
        if (ownsRefresh(claim, version)) { refreshClaim = null; refreshClaimUntil = null; }
    }
    public void requireReauthorization(Instant now) {
        status = "reauthorization_required"; encryptedAccessToken = null; encryptedRefreshToken = null;
        refreshClaim = null; refreshClaimUntil = null; tokenVersion = Math.incrementExact(tokenVersion); updatedAt = now;
    }
    public void disconnect(Instant now) { requireReauthorization(now); status = "disconnected"; }
    public void changeAccessScope() { accessVersion = Math.incrementExact(accessVersion); }
    @Override public String toString() { return "GitHubAccount[redacted]"; }
}
