package com.kopite.devspace.github.application;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service @Transactional(propagation = Propagation.NEVER)
public class GitHubSyncService {
    private final GitHubSyncStore store;
    private final GitHubSourceClient source;
    private final GitHubAccessService access;
    private final GitHubLinkStore links;
    private final Clock clock;
    public GitHubSyncService(GitHubSyncStore store, GitHubSourceClient source, GitHubAccessService access, GitHubLinkStore links, Clock projectClock) {
        this.store = store; this.source = source; this.access = access; this.links = links; this.clock = projectClock;
    }
    public UUID request(UUID user, UUID project, UUID link, String key) {
        links.get(user, project, link); access.refresh(user, link);
        return store.request(user, link, key, clock.instant());
    }
    public GitHubSyncStore.Job job(UUID user, UUID id) {
        UUID link = store.jobLink(user, id); if(!store.metadataOnlyJob(user,id))access.refresh(user, link); return store.job(user, id, clock.instant());
    }
    public UUID requestIssue(UUID user, UUID project, UUID link, long number, String key) {
        links.get(user, project, link); access.refresh(user, link); return store.requestIssue(user, link, number, key, clock.instant());
    }
    public void runOnce() {
        store.dispatchInbox(clock.instant());
        var claim = store.claim(clock.instant()); if (claim.isEmpty()) return;
        var work = claim.get();
        try {
            access.refreshForWorker(work.user(), work.link(), false);
            var link = links.get(work.user(), work.project(), work.link());
            if (link.fullName() == null) throw new GitHubException("GITHUB_ACCESS_DENIED");
            if (work.stage().equals("verify")) { store.reconcileCached(work, clock.instant()); return; }
            var page = source.collect(work.installation(), work.repository(), link.fullName(), work.stage(), work.page(), work.createdAt().minusSeconds(90L * 86400), work.pageSize());
            if (!page.unavailableNumbers().isEmpty()) access.refreshForWorker(work.user(), work.link(), true);
            store.apply(work, page, clock.instant());
        } catch (RuntimeException ex) {
            var now = clock.instant();
            var retry = now.plusSeconds(Math.min(300, 5L << Math.min(work.attempts(), 6)) + ThreadLocalRandom.current().nextLong(5));
            if (ex instanceof GitHubRateLimitException limit && limit.retryAt().isAfter(retry)) retry = limit.retryAt();
            store.fail(work, ex instanceof GitHubException github ? github.code() : "GITHUB_UNAVAILABLE", retry, now);
        }
    }
}
