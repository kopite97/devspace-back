package com.kopite.devspace.github.domain;
import java.time.Instant;
public class GitHubRateLimitException extends GitHubException {
    private final Instant retryAt;
    public GitHubRateLimitException(Instant retryAt) { super("GITHUB_RATE_LIMITED"); this.retryAt = retryAt; }
    public Instant retryAt() { return retryAt; }
}
