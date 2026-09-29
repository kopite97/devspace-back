package com.kopite.devspace.attachment.application;
import java.time.Instant;
import java.util.*;
public interface AttachmentCleanupStore {
    record Job(UUID id,UUID attachmentId,UUID documentId,String key,UUID token){}
    List<UUID> expired(Instant cutoff,int limit);
    List<UUID> due(Instant now,int limit);
    Optional<Job> get(UUID id);
    Optional<Job> lease(UUID id,Instant now,Instant until);
    boolean finish(Job job);
    void retry(Job job,Instant next);
    void purgeDocument(UUID document,Instant now);
    void purgeSpace(UUID space,Instant now);
    Optional<UUID> jobFor(UUID attachment);
}
