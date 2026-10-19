package com.kopite.devspace.github.application;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
@Transactional(propagation = Propagation.NEVER)
public class GitHubAuthorizationService {
    private final GitHubAccountStore accounts;
    private final GitHubSecretVault vault;
    private final GitHubAuthorizationClient client;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;
    private final String clientId, callback, authorizationUrl, installationUrl;
    public GitHubAuthorizationService(GitHubAccountStore accounts, GitHubSecretVault vault, GitHubAuthorizationClient client,
            Clock projectClock, @Value("${app.github.enabled:false}") boolean enabled,
            @Value("${app.github.client-id:}") String clientId, @Value("${app.github.callback-url:}") String callback,
            @Value("${app.github.authorization-url:https://github.com/login/oauth/authorize}") String authorizationUrl,
            @Value("${app.github.app-slug:}") String appSlug) {
        this.accounts = accounts; this.vault = vault; this.client = client; this.clock = projectClock;
        this.enabled = enabled; this.clientId = clientId; this.callback = callback; this.authorizationUrl = authorizationUrl;
        this.installationUrl = enabled ? "https://github.com/apps/" + appSlug + "/installations/new" : null;
    }
    public record AuthorizationRequest(String authorizationUrl, Instant expiresAt) {}
    public record AccountView(String status, Long githubUserId, String login, boolean reauthorizationRequired, String installationUrl) {}
    public AccountView account(UUID user) {
        if (!enabled) return new AccountView("not_configured", null, null, false, null);
        return accounts.account(user).map(a -> new AccountView(a.getStatus(), a.getStatus().equals("disconnected") ? null : a.getGithubUserId(), a.getStatus().equals("disconnected") ? null : a.getLogin(),
                a.getStatus().equals("reauthorization_required"), installationUrl)).orElseGet(() -> new AccountView("disconnected", null, null, false, installationUrl));
    }
    public AuthorizationRequest begin(UUID user, String sessionId) {
        requireEnabled();
        String state = randomToken(), verifier = randomToken(); Instant now = clock.instant();
        long version = accounts.account(user).map(GitHubAccount::getTokenVersion).orElse(0L);
        accounts.saveState(GitHubOAuthState.create(hash(state), user, hash(sessionId), vault.encrypt(user, "oauth-verifier", verifier), version, now), now);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier));
        return new AuthorizationRequest(authorizationUrl + "?client_id=" + encode(clientId) + "&redirect_uri=" + encode(callback)
                + "&state=" + state + "&code_challenge_method=S256&code_challenge=" + challenge, now.plusSeconds(600));
    }
    public void complete(UUID user, String sessionId, String state, String code, String error) {
        requireEnabled();
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}") || sessionId == null) throw new GitHubException("GITHUB_INVALID_STATE");
        var proof = accounts.consumeState(hash(state), user, hash(sessionId), clock.instant());
        String verifier = vault.decrypt(user, "oauth-verifier", proof.encryptedVerifier());
        if (error != null) throw new GitHubException("GITHUB_AUTHORIZATION_DENIED");
        if (code == null || code.isBlank() || code.length() > 2048) throw new GitHubException("GITHUB_INVALID_AUTHORIZATION");
        var tokens = client.exchange(code, verifier); var identity = client.identity(tokens.accessToken());
        accounts.authorize(user, hash(state), proof.accountVersion(), identity.id(), identity.login(), vault.encrypt(user, "access", tokens.accessToken()),
                vault.encrypt(user, "refresh", tokens.refreshToken()), tokens.expiresAt(), tokens.refreshExpiresAt(), clock.instant());
    }
    public String accessToken(UUID user) {
        return credential(user).token();
    }
    public record Credential(String token, long version) {
        @Override public String toString() { return "Credential[redacted]"; }
    }
    public Credential credential(UUID user) {
        requireEnabled();
        var claim = accounts.claimRefresh(user, clock.instant());
        if (claim.reauthorization()) throw new GitHubException("GITHUB_REAUTHORIZATION_REQUIRED");
        if (claim.encryptedAccessToken() != null) return new Credential(vault.decrypt(user, "access", claim.encryptedAccessToken()), claim.version());
        try {
            var tokens = client.refresh(vault.decrypt(user, "refresh", claim.encryptedRefreshToken()));
            boolean applied = accounts.finishRefresh(user, claim.claim(), claim.version(), vault.encrypt(user, "access", tokens.accessToken()),
                    vault.encrypt(user, "refresh", tokens.refreshToken()), tokens.expiresAt(), tokens.refreshExpiresAt(), clock.instant());
            if (!applied) throw new GitHubException("GITHUB_REFRESH_IN_PROGRESS");
            return new Credential(tokens.accessToken(), Math.incrementExact(claim.version()));
        } catch (RuntimeException ex) {
            if (ex instanceof GitHubRequestDeferredException) accounts.cancelRefresh(user, claim.claim(), claim.version());
            else accounts.failRefresh(user, claim.claim(), claim.version(), clock.instant());
            throw ex;
        }
    }
    private void requireEnabled() { if (!enabled) throw new GitHubException("GITHUB_NOT_CONFIGURED"); }
    public boolean rejectCredential(UUID user, long version) { return accounts.rejectCredential(user, version, clock.instant()); }
    public record Authorized<T>(T value, long version) {}
    public <T> Authorized<T> authorized(UUID user, java.util.function.Function<String, T> operation) {
        for (int attempt = 0; attempt < 2; attempt++) {
            var credential = credential(user);
            try { return new Authorized<>(operation.apply(credential.token()), credential.version()); }
            catch (GitHubException ex) {
                if (!ex.code().equals("GITHUB_REAUTHORIZATION_REQUIRED")) throw ex;
                if (rejectCredential(user, credential.version())) throw ex;
                // A concurrent refresh replaced this rejected token; retry only the newest token once.
            }
        }
        throw new GitHubException("GITHUB_REFRESH_IN_PROGRESS");
    }
    public void disconnect(UUID user) { accounts.disconnect(user, clock.instant()); }
    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String hash(String value) { return HexFormat.of().formatHex(digest(value)); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
