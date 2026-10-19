package com.kopite.devspace.github.application.port;

import java.time.Instant;
import java.util.*;

public interface GitHubAccessStore {
    default Optional<Claim> claim(UUID user, UUID link, Instant now) { return claim(user, link, now, false); }
    Optional<Claim> claim(UUID user, UUID link, Instant now, boolean force);
    void finish(Claim claim, GitHubRepositoryClient.Repository repository, String failure, Instant now);
    void invalidateInstallation(long installation, Long repository, Instant now);
    long installationVersion(long installation);
    void invalidateUser(long githubUser, Instant now);
    record Claim(UUID user, UUID link, UUID nonce, long accountVersion, long generation, long installation, long repository, long installationVersion) {}
}
