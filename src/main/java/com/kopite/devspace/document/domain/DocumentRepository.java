package com.kopite.devspace.document.domain;
import java.util.Optional;
import java.util.UUID;
public interface DocumentRepository {
    Optional<Document> find(UUID id,boolean lock);
    void insert(Document value);
    void delete(Document value);
    void flush();
    Optional<UUID> spaceOf(UUID id);
    long count(UUID space);
    java.util.List<DocumentSummary> page(UUID space,int limit,java.time.Instant at,UUID id);
    record DocumentSummary(UUID id,String title,Document.Status status,UUID authorId,java.time.Instant createdAt,java.time.Instant updatedAt,java.time.Instant publishedAt){}
}
