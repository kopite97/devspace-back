package com.kopite.devspace.github.application;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.application.model.GitHubLinkSnapshot;
import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service @Transactional(propagation = Propagation.NEVER)
public class GitHubConnectionService {
    private final GitHubAuthorizationService authorization;
    private final GitHubRepositoryClient client;
    private final GitHubLinkStore links;
    private final Clock clock;
    private final GitHubAccessService access;
    private final boolean enabled;
    public GitHubConnectionService(GitHubAuthorizationService authorization, GitHubRepositoryClient client, GitHubLinkStore links, Clock projectClock, GitHubAccessService access,@org.springframework.beans.factory.annotation.Value("${app.github.enabled:false}") boolean enabled) {
        this.authorization = authorization; this.client = client; this.links = links; this.clock = projectClock; this.access = access;
        this.enabled=enabled;
    }
    public GitHubRepositoryClient.Page<GitHubRepositoryClient.Installation> installations(UUID user, int page, int limit) {
        validate(page, limit); return authorization.authorized(user, token -> client.installations(token, page, limit)).value();
    }
    public GitHubRepositoryClient.Page<GitHubRepositoryClient.Repository> repositories(UUID user, long installation, int page, int limit) {
        validate(page, limit);
        if (installation <= 0) throw new GitHubException("VALIDATION_ERROR");
        return authorization.authorized(user, token -> client.repositories(token, installation, page, limit)).value();
    }
    public GitHubLinkSnapshot connect(UUID user, UUID project, long installation, long repository, String key) {
        links.requireProject(user, project, true);
        if (installation <= 0 || repository <= 0 || key == null || !key.matches("[!-~]{1,128}")) throw new GitHubException("VALIDATION_ERROR");
        var account = authorization.account(user);
        if (account.githubUserId() == null) throw new GitHubException("GITHUB_ACCOUNT_NOT_CONNECTED");
        long installationVersion = access.installationVersion(installation);
        var verified = authorization.authorized(user, token -> client.verify(token, installation, repository));
        return links.connect(user, project, account.githubUserId(), verified.version(), installation, installationVersion, verified.value(), key, clock.instant());
    }
    public List<GitHubLinkSnapshot> list(UUID user, UUID project) {
        // Bound request-side work; remaining expired connections stay visibly pending.
        if(enabled)links.list(user, project).stream().filter(l -> !l.accessStatus().equals("verified") || l.accessExpiresAt() == null
                || !l.accessExpiresAt().isAfter(clock.instant())).limit(1).forEach(l -> access.refresh(user, l.id()));
        return links.list(user, project).stream().map(this::safeMetadata).toList();
    }
    public GitHubLinkSnapshot get(UUID user, UUID project, UUID id) {
        links.get(user, project, id); access.refresh(user, id); return safeMetadata(links.get(user, project, id));
    }
    private GitHubLinkSnapshot safeMetadata(GitHubLinkSnapshot link) {
        if (enabled && link.accessStatus().equals("verified") && link.accessExpiresAt() != null && link.accessExpiresAt().isAfter(clock.instant())) return link;
        return new GitHubLinkSnapshot(link.id(), link.projectId(), link.installationId(), link.repositoryId(), null, null,
                link.privateRepository(), link.status(), link.accessStatus().equals("verified") ? "check_pending" : link.accessStatus(),
                link.paused(), link.revision(), link.accessExpiresAt(), link.createdAt(), link.updatedAt());
    }
    private static void validate(int page, int limit) { if (page < 1 || page > 100 || limit < 1 || limit > 100) throw new GitHubException("VALIDATION_ERROR"); }
}
