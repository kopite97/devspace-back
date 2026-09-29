package com.kopite.devspace.attachment.application;

import com.kopite.devspace.attachment.domain.Attachment;
import java.time.Instant;
import java.util.UUID;

/** Metadata for an attachment saved in a Document, distinct from a TEMP upload. */
public record AttachedSnapshot(UUID id,UUID documentId,String originalFilename,String contentType,long size,
        Attachment.Status status,String contentUrl,Instant createdAt) {
    public static AttachedSnapshot from(Attachment a) {
        return new AttachedSnapshot(a.getId(),a.getDocumentId(),a.getOriginalFilename(),a.getContentType(),a.getSize(),
                a.getStatus(),"/api/v2/attachments/"+a.getId()+"/content",a.getCreatedAt());
    }
}
