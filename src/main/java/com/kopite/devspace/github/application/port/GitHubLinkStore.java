package com.kopite.devspace.github.application.port;

import com.kopite.devspace.github.application.model.GitHubLinkSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GitHubLinkStore {
    void requireProject(UUID user, UUID project, boolean active);
    List<GitHubLinkSnapshot> list(UUID user, UUID project);
    GitHubLinkSnapshot get(UUID user, UUID project, UUID link);
    GitHubLinkSnapshot connect(UUID user, UUID project, long githubUser, long accountVersion, long installation, long installationVersion,
            GitHubRepositoryClient.Repository repository, String key, Instant now);
}
