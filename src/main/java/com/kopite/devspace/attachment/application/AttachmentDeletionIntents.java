package com.kopite.devspace.attachment.application;
import java.time.Instant;
import java.util.UUID;
public interface AttachmentDeletionIntents {
    void enqueue(UUID attachment,UUID document,String key,String reason,Instant now);
    boolean pending(UUID attachment);
    boolean anyPending(java.util.Collection<UUID> attachments);
}
