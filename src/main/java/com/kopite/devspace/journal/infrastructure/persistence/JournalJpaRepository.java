package com.kopite.devspace.journal.infrastructure.persistence;

import com.kopite.devspace.journal.domain.Journal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface JournalJpaRepository extends JpaRepository<Journal, UUID> {
    Optional<Journal> findByWorkspaceIdAndId(UUID workspaceId, UUID id);
    Optional<Journal> findByDocumentSpaceId(UUID documentSpaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Journal t where t.workspaceId = :workspaceId and t.id = :id")
    Optional<Journal> lockOwned(UUID workspaceId, UUID id);
}
