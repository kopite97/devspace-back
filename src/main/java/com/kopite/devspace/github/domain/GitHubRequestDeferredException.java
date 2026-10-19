package com.kopite.devspace.github.domain;
import java.time.Instant;
/** Dispatch was refused locally; no request was sent to GitHub. */
public class GitHubRequestDeferredException extends GitHubRateLimitException {
    public GitHubRequestDeferredException(Instant retryAt) { super(retryAt); }
}
