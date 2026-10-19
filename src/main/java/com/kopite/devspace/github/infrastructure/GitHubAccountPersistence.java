package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.GitHubAccountStore;
import com.kopite.devspace.github.domain.*;
import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import jakarta.persistence.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public class GitHubAccountPersistence implements GitHubAccountStore {
    private final EntityManager em;
    private final CurrentUserService users;
    private final PersonalWorkspaceRepository workspaces;
    public GitHubAccountPersistence(EntityManager em, CurrentUserService users, PersonalWorkspaceRepository workspaces) {
        this.em = em; this.users = users; this.workspaces = workspaces;
    }
    @Override public void saveState(GitHubOAuthState state, Instant now) {
        users.resolve(state.getUserId());
        em.createQuery("delete from GitHubOAuthState s where s.userId=:user and s.expiresAt<=:now")
                .setParameter("user", state.getUserId()).setParameter("now", now).executeUpdate();
        em.persist(state);
    }
    @Override public AuthorizationProof consumeState(String hash, UUID user, String sessionHash, Instant now) {
        users.resolve(user);
        var state = em.find(GitHubOAuthState.class, hash, LockModeType.PESSIMISTIC_WRITE);
        if (state == null) throw new GitHubException("GITHUB_INVALID_STATE");
        return new AuthorizationProof(state.consume(user, sessionHash, now), state.getAccountVersion());
    }
    @Override @Transactional(readOnly = true) public Optional<GitHubAccount> account(UUID user) {
        users.resolve(user); return Optional.ofNullable(em.find(GitHubAccount.class, user));
    }
    @Override public void authorize(UUID user, String stateHash, long expectedVersion, long githubId, String login, String access, String refresh, Instant expires, Instant refreshExpires, Instant now) {
        workspaces.lockByOwnerId(user).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        users.resolve(user);
        var account = em.find(GitHubAccount.class, user, LockModeType.PESSIMISTIC_WRITE);
        var state = em.find(GitHubOAuthState.class, stateHash);
        if (state == null || !state.getUserId().equals(user) || state.getConsumedAt() == null || !state.getExpiresAt().isAfter(now)
                || (account == null ? 0 : account.getTokenVersion()) != expectedVersion)
            throw new GitHubException("GITHUB_INVALID_STATE");
        if (account == null) em.persist(GitHubAccount.connect(user, githubId, login, access, refresh, expires, refreshExpires, now));
        else account.authorize(githubId, login, access, refresh, expires, refreshExpires, now);
    }
    @Override public RefreshClaim claimRefresh(UUID user, Instant now) {
        users.resolve(user);
        var account = required(user);
        if (!account.getStatus().equals("connected")) return new RefreshClaim(null, account.getTokenVersion(), null, null, true);
        if (account.getExpiresAt().isAfter(now.plusSeconds(60)))
            return new RefreshClaim(null, account.getTokenVersion(), null, account.getEncryptedAccessToken(), false);
        UUID claim = account.claimRefresh(now);
        return new RefreshClaim(claim, account.getTokenVersion(), account.getEncryptedRefreshToken(), null, claim == null);
    }
    @Override public boolean finishRefresh(UUID user, UUID claim, long version, String access, String refresh, Instant expires, Instant refreshExpires, Instant now) {
        var account = required(user);
        if (!account.ownsRefresh(claim, version)) return false;
        if (!account.getRefreshClaimUntil().isAfter(now)) { account.requireReauthorization(now); return false; }
        account.authorize(account.getGithubUserId(), account.getLogin(), access, refresh, expires, refreshExpires, now);
        return true;
    }
    @Override public void failRefresh(UUID user, UUID claim, long version, Instant now) {
        var account = required(user);
        if (account.ownsRefresh(claim, version)) account.requireReauthorization(now);
    }
    @Override public void cancelRefresh(UUID user, UUID claim, long version) { required(user).cancelRefresh(claim, version); }
    @Override public boolean rejectCredential(UUID user, long version, Instant now) {
        var account = required(user);
        if (account.getTokenVersion() != version) return false;
        account.requireReauthorization(now); account.changeAccessScope(); return true;
    }
    private GitHubAccount required(UUID user) {
        var value = em.find(GitHubAccount.class, user, LockModeType.PESSIMISTIC_WRITE);
        if (value == null) throw new GitHubException("GITHUB_ACCOUNT_NOT_CONNECTED");
        return value;
    }
    @Override public void disconnect(UUID user, Instant now) {
        workspaces.lockByOwnerId(user).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        users.resolve(user);
        long links = em.createQuery("select count(l) from GitHubRepositoryLink l where l.userId=:user and l.status<>'disconnected'", Long.class)
                .setParameter("user", user).getSingleResult();
        if (links != 0) throw new GitHubException("GITHUB_ACCOUNT_IN_USE");
        var account = em.find(GitHubAccount.class, user, LockModeType.PESSIMISTIC_WRITE);
        if (account != null) account.disconnect(now);
        em.createQuery("delete from GitHubOAuthState s where s.userId=:user").setParameter("user", user).executeUpdate();
    }
}
