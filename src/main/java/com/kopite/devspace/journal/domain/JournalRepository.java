package com.kopite.devspace.journal.domain;

import java.util.Optional;
import java.util.UUID;

public interface JournalRepository {
    Journal save(Journal task);
    void delete(Journal journal);
    Optional<Journal> findOwned(UUID workspaceId, UUID id);
    Optional<Journal> lockOwned(UUID workspaceId, UUID id);
    Optional<Journal> findByDocumentSpace(UUID spaceId);
    void flush();
}
