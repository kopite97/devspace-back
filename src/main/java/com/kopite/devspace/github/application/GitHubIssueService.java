package com.kopite.devspace.github.application;

import com.kopite.devspace.github.application.port.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.UUID;

@Service @Transactional(propagation = Propagation.NEVER)
public class GitHubIssueService {
    private final GitHubAccessService access;
    private final GitHubLinkStore links;
    private final GitHubIssueQueries queries;
    private final Clock clock;
    public GitHubIssueService(GitHubAccessService access, GitHubLinkStore links, GitHubIssueQueries queries, Clock projectClock) {
        this.access = access; this.links = links; this.queries = queries; this.clock = projectClock;
    }
    private void prepare(UUID user, UUID project, UUID link) { links.get(user, project, link); access.refresh(user, link); }
    public GitHubIssueQueries.Issues issues(UUID user, UUID project, UUID link, GitHubIssueQueries.Filter filter, String cursor) {
        prepare(user, project, link); return queries.issues(user, link, filter, cursor, clock.instant());
    }
    public GitHubIssueQueries.Labels labels(UUID user, UUID project, UUID link, int limit, String cursor) {
        prepare(user, project, link); return queries.labels(user, link, limit, cursor, clock.instant());
    }
    public GitHubIssueQueries.SyncStatus status(UUID user, UUID project, UUID link) { prepare(user, project, link); return queries.status(user, link, clock.instant()); }
}
