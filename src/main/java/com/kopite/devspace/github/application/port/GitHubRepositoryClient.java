package com.kopite.devspace.github.application.port;

import java.util.List;

public interface GitHubRepositoryClient {
    Page<Installation> installations(String userToken, int page, int limit);
    Page<Repository> repositories(String userToken, long installationId, int page, int limit);
    Repository verify(String userToken, long installationId, long repositoryId);
    record Page<T>(List<T> items, long total, Integer nextPage) { public Page { items = List.copyOf(items); } }
    record Installation(long id, String accountLogin, String accountType, boolean suspended, boolean issuesReadable) {}
    record Repository(long id, String fullName, String htmlUrl, boolean privateRepository, boolean issuesEnabled) {}
}
