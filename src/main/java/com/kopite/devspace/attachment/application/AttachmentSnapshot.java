package com.kopite.devspace.attachment.application;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kopite.devspace.attachment.domain.Attachment;
import java.time.Instant;
import java.util.UUID;
public record AttachmentSnapshot(UUID id,UUID documentId,String originalFilename,String contentType,long size,Attachment.Status status,String contentUrl,Instant createdAt,@JsonIgnore Long dataRevision) {
    public static AttachmentSnapshot from(Attachment a,long revision){return new AttachmentSnapshot(a.getId(),a.getDocumentId(),a.getOriginalFilename(),a.getContentType(),a.getSize(),a.getStatus(),"/api/v2/attachments/"+a.getId()+"/content",a.getCreatedAt(),revision);}
}
