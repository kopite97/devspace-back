package com.kopite.devspace.github.application;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;

@Service @Transactional(propagation = Propagation.NEVER)
public class GitHubRecoveryService {
    private final GitHubRecoveryStore store;
    private final GitHubDeliveryClient client;
    private final Clock clock;
    public GitHubRecoveryService(GitHubRecoveryStore store, GitHubDeliveryClient client, Clock projectClock) {
        this.store = store; this.client = client; this.clock = projectClock;
    }
    public void runOnce() {
        var claim = store.claim(clock.instant()); if (claim.isEmpty()) return;
        var work = claim.get();
        try {
            if (work.redeliveryId() != null) {
                client.redeliver(work.redeliveryId()); store.finishRedelivery(work, true, clock.instant(), clock.instant());
            } else store.accept(work, client.deliveries(work.cursor()), clock.instant());
        } catch (RuntimeException ex) {
            var now = clock.instant(); var retry = now.plusSeconds(600);
            if (ex instanceof GitHubRateLimitException limit && limit.retryAt().isAfter(retry)) retry = limit.retryAt();
            if (work.redeliveryId() != null) store.finishRedelivery(work, false, retry, now);
            else store.fail(work, ex instanceof GitHubException github ? github.code() : "GITHUB_UNAVAILABLE", retry, now);
        }
    }
}
