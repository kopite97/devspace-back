package com.kopite.devspace.github.application.port;

import java.time.Instant;
import java.util.List;

public interface GitHubSourceClient {
    Page collect(long installation, long repository, String fullName, String stage, int page, Instant since);
    default Page collect(long installation, long repository, String fullName, String stage, int page, Instant since, int pageSize) {
        return collect(installation, repository, fullName, stage, page, since);
    }
    record Issue(long id, long number, String title, String body, String state, String reason, String htmlUrl,
            List<Long> labelIds, List<Long> assigneeIds, String contentHash, boolean contentOverflow, Instant updatedAt) {}
    record Label(long id, String name, String color) {}
    record Page(List<Issue> issues, List<Label> labels, boolean more, List<Long> unavailableNumbers) {
        public Page(List<Issue> issues, List<Label> labels, boolean more) { this(issues, labels, more, List.of()); }
        public Page { issues = List.copyOf(issues); labels = List.copyOf(labels); unavailableNumbers = List.copyOf(unavailableNumbers); }
    }
}
