package com.kopite.devspace.github.application;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.UUID;

@Service @Transactional(propagation = Propagation.NEVER)
public class GitHubAccessService {
    private final GitHubAuthorizationService authorization;
    private final GitHubAccessStore access;
    private final GitHubRepositoryClient client;
    private final Clock clock;
    public GitHubAccessService(GitHubAuthorizationService authorization, GitHubAccessStore access, GitHubRepositoryClient client, Clock projectClock) {
        this.authorization = authorization; this.access = access; this.client = client; this.clock = projectClock;
    }
    public void refresh(UUID user, UUID link) {
        refresh(user, link, false);
    }
    public void refresh(UUID user, UUID link, boolean force) {
        refresh(user, link, force, false);
    }
    public void refreshForWorker(UUID user, UUID link, boolean force) { refresh(user, link, force, true); }
    private void refresh(UUID user, UUID link, boolean force, boolean propagateFailure) {
        // Token renewal must precede the claim so it cannot invalidate its own version proof.
        GitHubAuthorizationService.Credential credential;
        try { credential = authorization.credential(user); }
        catch (GitHubException ex) {
            if (ex.code().equals("GITHUB_NOT_CONFIGURED")) throw ex;
            access.claim(user, link, clock.instant());
            if (propagateFailure) throw ex;
            return;
        }
        var claim = access.claim(user, link, clock.instant(), force);
        if (claim.isEmpty()) return;
        var proof = claim.get();
        if (proof.accountVersion() != credential.version()) {
            access.finish(proof, null, "check_pending", clock.instant()); return;
        }
        try {
            var repository = client.verify(credential.token(), proof.installation(), proof.repository());
            access.finish(proof, repository, null, clock.instant());
        } catch (GitHubException ex) {
            if (ex.code().equals("GITHUB_REAUTHORIZATION_REQUIRED"))
                authorization.rejectCredential(user, credential.version());
            String reason = switch (ex.code()) {
                case "GITHUB_ACCESS_DENIED", "GITHUB_ISSUES_DISABLED" -> "denied";
                case "GITHUB_REAUTHORIZATION_REQUIRED", "GITHUB_ACCOUNT_NOT_CONNECTED" -> "reauthorization_required";
                default -> "check_pending";
            };
            access.finish(proof, null, reason, clock.instant());
            if (propagateFailure) throw ex;
        }
    }
    public long installationVersion(long installation) { return access.installationVersion(installation); }
}
