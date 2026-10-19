package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.GitHubRepositoryClient;
import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;

@Component
public class HttpGitHubRepositoryClient implements GitHubRepositoryClient {
    private final GitHubProperties settings;
    private final GitHubHttpTransport http;
    public HttpGitHubRepositoryClient(GitHubProperties settings, GitHubHttpTransport http) { this.settings = settings; this.http = http; }
    @Override public Page<Installation> installations(String token, int page, int limit) {
        JsonNode result = get(token, "/user/installations?per_page=" + limit + "&page=" + page);
        List<Installation> items = new ArrayList<>();
        for (var node : result.path("installations")) items.add(new Installation(node.path("id").asLong(),
                node.path("account").path("login").asString(), node.path("account").path("type").asString(),
                node.hasNonNull("suspended_at"), Set.of("read", "write").contains(node.path("permissions").path("issues").asString(""))));
        return page(items, result.path("total_count").asLong(), page, limit);
    }
    @Override public Page<Repository> repositories(String token, long installation, int page, int limit) {
        JsonNode result = get(token, "/user/installations/" + installation + "/repositories?per_page=" + limit + "&page=" + page);
        List<Repository> items = new ArrayList<>();
        for (var node : result.path("repositories")) items.add(new Repository(node.path("id").asLong(),
                node.path("full_name").asString(), node.path("html_url").asString(), node.path("private").asBoolean(), node.path("has_issues").asBoolean()));
        return page(items, result.path("total_count").asLong(), page, limit);
    }
    @Override public Repository verify(String token, long installation, long repository) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        boolean allowed = false;
        for (int p = 1; p <= 100; p++) {
            if (System.nanoTime() >= deadline) throw new GitHubException("GITHUB_UNAVAILABLE");
            var page = installations(token, p, 100);
            var found = page.items().stream().filter(i -> i.id() == installation).findFirst();
            if (found.isPresent()) {
                allowed = !found.get().suspended() && found.get().issuesReadable(); break;
            }
            if (page.nextPage() == null) break;
        }
        if (!allowed) throw new GitHubException("GITHUB_ACCESS_DENIED");
        for (int p = 1; p <= 100; p++) {
            if (System.nanoTime() >= deadline) throw new GitHubException("GITHUB_UNAVAILABLE");
            var page = repositories(token, installation, p, 100);
            var found = page.items().stream().filter(r -> r.id() == repository).findFirst();
            if (found.isPresent()) {
                if (!found.get().issuesEnabled()) throw new GitHubException("GITHUB_ISSUES_DISABLED");
                return found.get();
            }
            if (page.nextPage() == null) break;
        }
        throw new GitHubException("GITHUB_ACCESS_DENIED");
    }
    private JsonNode get(String token, String path) { return http.request(URI.create(settings.getApiUrl() + path), "GET", token, null, null); }
    private static <T> Page<T> page(List<T> rows, long total, int page, int limit) {
        return new Page<>(rows, total, (long) page * limit < total ? page + 1 : null);
    }
}
