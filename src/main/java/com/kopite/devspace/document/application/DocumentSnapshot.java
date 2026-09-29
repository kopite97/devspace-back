package com.kopite.devspace.document.application;
import com.kopite.devspace.document.domain.Document;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.*;
import com.kopite.devspace.attachment.application.AttachedSnapshot;
public record DocumentSnapshot(UUID id,UUID documentSpaceId,String title,@JsonRawValue String content,List<UUID> attachmentIds,
        List<AttachedSnapshot> attachments,Document.Status status,UUID authorId,Instant createdAt,Instant updatedAt,Instant publishedAt,@JsonIgnore Long dataRevision) {
    public static DocumentSnapshot from(Document d,List<AttachedSnapshot> attachments,long revision) {
        return new DocumentSnapshot(d.getId(),d.getDocumentSpaceId(),d.getTitle(),d.getContent(),attachments.stream().map(AttachedSnapshot::id).toList(),List.copyOf(attachments),d.getStatus(),d.getAuthorId(),d.getCreatedAt(),d.getUpdatedAt(),d.getPublishedAt(),revision);
    }
}
