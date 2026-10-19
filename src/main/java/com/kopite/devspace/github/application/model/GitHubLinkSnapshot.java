package com.kopite.devspace.github.application.model;

import com.kopite.devspace.github.domain.GitHubRepositoryLink;
import java.time.Instant;
import java.util.UUID;

public record GitHubLinkSnapshot(UUID id, UUID projectId, long installationId, long repositoryId, String fullName,
        String htmlUrl, boolean privateRepository, String status, String accessStatus, boolean paused,
        long revision, Instant accessExpiresAt, Instant createdAt, Instant updatedAt) {
    public static GitHubLinkSnapshot from(GitHubRepositoryLink link) {
        return new GitHubLinkSnapshot(link.getId(), link.getProjectId(), link.getInstallationId(), link.getRepositoryId(),
                link.getFullName(), link.getHtmlUrl(), link.isPrivateRepository(), link.getStatus(), link.getAccessStatus(),
                link.isPaused(), link.getRevision(), link.getAccessExpiresAt(), link.getCreatedAt(), link.getUpdatedAt());
    }
}
