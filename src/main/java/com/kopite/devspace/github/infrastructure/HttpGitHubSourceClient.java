package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Component
public class HttpGitHubSourceClient implements GitHubSourceClient {
    private final GitHubProperties settings;
    private final GitHubHttpTransport http;
    private final GitHubInstallationTokens tokens;
    public HttpGitHubSourceClient(GitHubProperties settings, GitHubHttpTransport http, GitHubInstallationTokens tokens) {
        this.settings = settings; this.http = http; this.tokens = tokens;
    }
    @Override public Page collect(long installation, long repository, String fullName, String stage, int page, Instant since) {
        return collect(installation, repository, fullName, stage, page, since, 100);
    }
    @Override public Page collect(long installation, long repository, String fullName, String stage, int page, Instant since, int pageSize) {
        if (stage.matches("issue:[1-9][0-9]{0,17}")) return individual(installation, repository, fullName, Long.parseLong(stage.substring(6)));
        if (!fullName.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || page < 1 || pageSize < 1 || pageSize > 100 || !Set.of("labels", "open", "closed").contains(stage))
            throw new GitHubException("VALIDATION_ERROR");
        String path = "/repos/" + fullName + (stage.equals("labels") ? "/labels?" : "/issues?state=" + stage + "&sort=created&direction=asc&")
                + "per_page=" + pageSize + "&page=" + page;
        if (stage.equals("closed")) path += "&since=" + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8);
        JsonNode rows;
        try { rows = http.request(URI.create(settings.getApiUrl() + path), "GET", tokens.token(installation, repository), null, null); }
        catch (GitHubException ex) {
            if (ex.code().equals("GITHUB_REAUTHORIZATION_REQUIRED")) { tokens.invalidate(installation); throw new GitHubException("GITHUB_UNAVAILABLE"); }
            throw ex;
        }
        if (!rows.isArray()) throw new GitHubException("GITHUB_UNAVAILABLE");
        List<Issue> issues = new ArrayList<>(); List<Label> labels = new ArrayList<>();
        for (var row : rows) {
            if (stage.equals("labels")) {
                long id = row.path("id").asLong(); String color = row.path("color").asString("");
                if (id <= 0 || !color.matches("[a-fA-F0-9]{6}")) throw new GitHubException("GITHUB_UNAVAILABLE");
                labels.add(new Label(id, row.path("name").asString(""), color));
            } else if (!row.hasNonNull("pull_request")) issues.add(issue(row));
        }
        return new Page(issues, labels, rows.size() == pageSize);
    }
    private Page individual(long installation, long repository, String fullName, long number) {
        if (!fullName.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new GitHubException("VALIDATION_ERROR");
        try {
            var row = http.request(URI.create(settings.getApiUrl() + "/repos/" + fullName + "/issues/" + number), "GET", tokens.token(installation, repository), null, null);
            if (row.hasNonNull("pull_request")) return new Page(List.of(), List.of(), false, List.of(number));
            return new Page(List.of(issue(row)), List.of(), false);
        } catch (GitHubException ex) {
            if (ex.upstreamStatus() == 404 || ex.upstreamStatus() >= 300 && ex.upstreamStatus() < 400)
                return new Page(List.of(), List.of(), false, List.of(number));
            if (ex.code().equals("GITHUB_REAUTHORIZATION_REQUIRED")) { tokens.invalidate(installation); throw new GitHubException("GITHUB_UNAVAILABLE"); }
            throw ex;
        }
    }
    static Issue issue(JsonNode row) {
        try {
            long id = row.path("id").asLong(), number = row.path("number").asLong();
            String title = row.path("title").asString(""), body = row.path("body").asString("");
            String state = row.path("state").asString("");
            if (id <= 0 || number <= 0 || !Set.of("open", "closed").contains(state)) throw new IllegalArgumentException();
            boolean overflow = title.length() > 160 || body.length() > 10000 || title.isBlank();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((title + "\u0000" + body).getBytes(StandardCharsets.UTF_8)));
            return new Issue(id, number, title.length() > 160 ? "" : title, body.length() > 10000 ? "" : body,
                    state, row.path("state_reason").asString(null), row.path("html_url").asString(""), ids(row.path("labels")), ids(row.path("assignees")),
                    hash, overflow, Instant.parse(row.path("updated_at").asString("")));
        } catch (Exception ex) { throw new GitHubException("GITHUB_UNAVAILABLE"); }
    }
    private static List<Long> ids(JsonNode nodes) {
        List<Long> ids = new ArrayList<>(); for (var node : nodes) { long id = node.path("id").asLong(); if (id > 0) ids.add(id); }
        return List.copyOf(ids);
    }
}
