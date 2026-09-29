package com.kopite.devspace.documentspace.domain;
import java.util.Optional;
import java.util.UUID;
public interface DocumentSpaceRepository {
    Optional<DocumentSpace> find(UUID id,boolean lock);
    void insert(DocumentSpace value);
    void delete(DocumentSpace value);
    void flush();
}
