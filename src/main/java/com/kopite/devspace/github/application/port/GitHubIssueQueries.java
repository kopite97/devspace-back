package com.kopite.devspace.github.application.port;

import java.time.Instant;
import java.util.*;

public interface GitHubIssueQueries {
    Issues issues(UUID user, UUID link, Filter filter, String cursor, Instant now);
    Labels labels(UUID user, UUID link, int limit, String cursor, Instant now);
    SyncStatus status(UUID user, UUID link, Instant now);
    record Filter(String state, List<Long> labelIds, List<Long> assigneeIds, int limit) {
        public Filter {
            if (limit < 1 || limit > 100 || state != null && !Set.of("open","closed").contains(state))
                throw new com.kopite.devspace.github.domain.GitHubException("VALIDATION_ERROR");
            labelIds = normalize(labelIds); assigneeIds = normalize(assigneeIds);
        }
        private static List<Long> normalize(List<Long> values) {
            if (values == null) return List.of();
            if (values.size() > 20 || values.stream().anyMatch(v -> v == null || v <= 0))
                throw new com.kopite.devspace.github.domain.GitHubException("VALIDATION_ERROR");
            return values.stream().distinct().sorted().toList();
        }
    }
    record Issues(List<GitHubSourceClient.Issue> items, long total, String nextCursor, Coverage coverage) {}
    record Labels(List<GitHubSourceClient.Label> items, long total, String nextCursor) {}
    record Coverage(String state, Instant lastSuccessfulSyncAt, int closedUpdatedWithinDays) {}
    record SyncStatus(Coverage coverage, GitHubSyncStore.Job latestJob,boolean paused,String projectStatus,boolean baselineReady,
            Instant lastTaskAppliedAt,GitHubSyncStore.Job latestCollectionJob,GitHubSyncStore.Job latestTaskJob) {}
}
