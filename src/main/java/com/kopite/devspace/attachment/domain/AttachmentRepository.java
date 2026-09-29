package com.kopite.devspace.attachment.domain;
import java.util.Optional;
import java.util.UUID;
public interface AttachmentRepository {
    Optional<Attachment> find(UUID id,boolean lock);
    void insert(Attachment value);
    void delete(Attachment value);
    void flush();
    java.util.List<UUID> attachedIds(UUID document);
    java.util.List<Attachment> attached(UUID document);
    Optional<UUID> documentOf(UUID attachment);
}
