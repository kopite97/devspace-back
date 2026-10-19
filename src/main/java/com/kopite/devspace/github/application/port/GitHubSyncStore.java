package com.kopite.devspace.github.application.port;

import java.time.Instant;
import java.util.*;

public interface GitHubSyncStore {
    UUID enqueue(UUID link, Instant now);
    UUID request(UUID user, UUID link, String key, Instant now);
    UUID requestIssue(UUID user, UUID link, long number, String key, Instant now);
    UUID enqueueIssue(UUID link, long number, Instant now);
    Optional<Work> claim(Instant now);
    void apply(Work work, GitHubSourceClient.Page page, Instant now);
    void reconcileCached(Work work, Instant now);
    void fail(Work work, String code, Instant retryAt, Instant now);
    void dispatchInbox(Instant now);
    Job job(UUID user, UUID id, Instant now);
    UUID jobLink(UUID user, UUID id);
    boolean metadataOnlyJob(UUID user,UUID id);
    record Work(UUID id, UUID link, UUID workspace, UUID project, UUID user, long installation, long repository,
            long generation, UUID nonce, UUID scan, String stage, int page, int attempts, Instant createdAt, int pageSize) {}
    record Job(UUID id, UUID linkId, String state, String stage, int page, int attempts, String errorCode, Instant createdAt, Instant updatedAt,
            int completedCount,int failedCount,List<Item> items,String kind) {}
    record Item(long issueId,String state,UUID taskId,String errorCode) {}
}
