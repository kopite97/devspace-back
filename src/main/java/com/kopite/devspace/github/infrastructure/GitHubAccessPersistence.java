package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.*;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import jakarta.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Repository @Transactional
public class GitHubAccessPersistence implements GitHubAccessStore {
    private final EntityManager em;
    private final PersonalWorkspaceRepository workspaces;
    private final JdbcTemplate jdbc;
    public GitHubAccessPersistence(EntityManager em, PersonalWorkspaceRepository workspaces, JdbcTemplate jdbc) {
        this.em = em; this.workspaces = workspaces; this.jdbc = jdbc;
    }
    @Override public Optional<Claim> claim(UUID user, UUID id, Instant now) { return claim(user, id, now, false); }
    @Override public Optional<Claim> claim(UUID user, UUID id, Instant now, boolean force) {
        workspaces.lockByOwnerId(user).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        var account = em.find(GitHubAccount.class, user, LockModeType.PESSIMISTIC_WRITE);
        var initial = em.find(GitHubRepositoryLink.class, id);
        if (initial == null || !initial.getUserId().equals(user)) throw new GitHubException("RESOURCE_NOT_FOUND");
        long installationVersion = installationVersion(initial.getInstallationId());
        var link = em.find(GitHubRepositoryLink.class, id, LockModeType.PESSIMISTIC_WRITE);
        em.refresh(link);
        if (link == null || !link.getUserId().equals(user)) throw new GitHubException("RESOURCE_NOT_FOUND");
        if (account == null || !account.getStatus().equals("connected") || account.getGithubUserId() != link.getGithubUserId()) {
            link.deny("reauthorization_required", now);
            if (account != null) account.changeAccessScope();
            return Optional.empty();
        }
        if (!force && link.accessible(account.getTokenVersion(), now)) return Optional.empty();
        UUID claim = link.claimAccess(now);
        if (claim == null) return Optional.empty();
        account.changeAccessScope();
        return Optional.of(new Claim(user, id, claim, account.getTokenVersion(), link.getGeneration(), link.getInstallationId(), link.getRepositoryId(), installationVersion));
    }
    @Override public void finish(Claim proof, GitHubRepositoryClient.Repository repository, String failure, Instant now) {
        workspaces.lockByOwnerId(proof.user()).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        var account = em.find(GitHubAccount.class, proof.user(), LockModeType.PESSIMISTIC_WRITE);
        long installationVersion = installationVersion(proof.installation());
        var link = em.find(GitHubRepositoryLink.class, proof.link(), LockModeType.PESSIMISTIC_WRITE);
        if (account == null || link == null || !link.ownsAccessClaim(proof.nonce(), proof.generation(), now)) return;
        if (account.getTokenVersion() != proof.accountVersion() || !account.getStatus().equals("connected") || installationVersion != proof.installationVersion()) {
            link.deny("check_pending", now); account.changeAccessScope(); return;
        }
        if (repository != null && repository.id() == proof.repository() && repository.issuesEnabled())
            link.grant(proof.accountVersion(), repository.fullName(), repository.htmlUrl(), repository.privateRepository(), now);
        else link.deny(failure == null ? "denied" : failure, now);
        account.changeAccessScope();
    }
    @Override public void invalidateInstallation(long installation, Long repository, Instant now) {
        jdbc.update("insert into github_installation_epochs(installation_id,generation) values(?,2) on conflict(installation_id) do update set generation=github_installation_epochs.generation+1", installation);
        // A single atomic update invalidates both cached grants and in-flight verification proofs.
        // No account/workspace locks are taken after this update, avoiding inverse lock order.
        jdbc.update("update github_repository_links set access_status='check_pending', access_expires_at=?, generation=generation+1, access_claim=null, access_claim_until=null where installation_id=? and (?::bigint is null or repository_id=?)",
                java.sql.Timestamp.from(now), installation, repository, repository);
    }
    @Override public long installationVersion(long installation) {
        jdbc.update("insert into github_installation_epochs(installation_id) values(?) on conflict do nothing", installation);
        return jdbc.queryForObject("select generation from github_installation_epochs where installation_id=? for update", Long.class, installation);
    }
    @Override public void invalidateUser(long githubUser, Instant now) {
        jdbc.update("update github_accounts set status='reauthorization_required', encrypted_access_token=null, encrypted_refresh_token=null, refresh_claim=null, refresh_claim_until=null, token_version=token_version+1, access_version=access_version+1, updated_at=? where github_user_id=? and status<>'disconnected'",
                java.sql.Timestamp.from(now), githubUser);
    }
}
