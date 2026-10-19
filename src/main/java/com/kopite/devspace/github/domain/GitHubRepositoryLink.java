package com.kopite.devspace.github.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "github_repository_links") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubRepositoryLink {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID workspaceId;
    @Column(nullable = false, updatable = false) private UUID projectId;
    @Column(nullable = false, updatable = false) private UUID userId;
    @Column(nullable = false) private long githubUserId;
    @Column(nullable = false) private long installationId;
    @Column(nullable = false) private long repositoryId;
    @Column(nullable = false, columnDefinition = "text") private String fullName;
    @Column(nullable = false, columnDefinition = "text") private String htmlUrl;
    @Column(nullable = false) private boolean privateRepository;
    @Column(nullable = false, columnDefinition = "text") private String status;
    @Column(nullable = false, columnDefinition = "text") private String accessStatus;
    private Instant accessExpiresAt;
    @Column(nullable = false) private long accessAccountVersion;
    private UUID accessClaim;
    private Instant accessClaimUntil;
    @Column(nullable = false) private long revision;
    @Column(nullable = false) private long generation;
    @Column(nullable = false) private boolean paused;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    public static GitHubRepositoryLink connect(UUID workspace, UUID project, UUID user, long githubUser, long installation,
            long repository, String name, String url, boolean privateRepo, Instant now) {
        if (githubUser <= 0 || installation <= 0 || repository <= 0 || name == null || !name.matches("[^/]+/[^/]+"))
            throw new GitHubException("VALIDATION_ERROR");
        var link = new GitHubRepositoryLink(); link.id = UUID.randomUUID(); link.workspaceId = workspace;
        link.projectId = project; link.userId = user; link.githubUserId = githubUser; link.installationId = installation;
        link.repositoryId = repository; link.fullName = name; link.htmlUrl = url; link.privateRepository = privateRepo;
        link.status = "initializing"; link.accessStatus = "verified"; link.accessExpiresAt = now.plusSeconds(60);
        link.revision = 1; link.generation = 1; link.createdAt = now; link.updatedAt = now; return link;
    }
    public void checkRevision(long expected) {
        if (expected < 1 || expected != revision || revision == 9007199254740991L) throw new GitHubException("REVISION_CONFLICT");
    }
    public void reconnect(long githubUser,long installation,String name,String url,boolean privateRepo,Instant now) {
        if(!status.equals("disconnected"))throw new GitHubException("GITHUB_REPOSITORY_ALREADY_CONNECTED");
        checkRevision(revision);githubUserId=githubUser;installationId=installation;fullName=name;htmlUrl=url;privateRepository=privateRepo;
        status="initializing";paused=false;generation++;revision++;updatedAt=now;
    }
    public void pause(long expected, boolean value, Instant now) {
        checkRevision(expected);
        if (status.equals("disconnecting") || status.equals("disconnected")) throw new GitHubException("GITHUB_LINK_INACTIVE");
        if (paused == value) return;
        paused = value; generation++; revision++; updatedAt = now;
    }
    public boolean accessible(long accountVersion, Instant now) {
        return !status.equals("disconnecting") && !status.equals("disconnected") && accessStatus.equals("verified")
                && accessAccountVersion == accountVersion && accessExpiresAt != null && accessExpiresAt.isAfter(now);
    }
    public UUID claimAccess(Instant now) {
        if (accessClaim != null && accessClaimUntil.isAfter(now)) return null;
        if (status.equals("disconnecting") || status.equals("disconnected")) return null;
        accessClaim = UUID.randomUUID(); accessClaimUntil = now.plusSeconds(60);
        accessStatus = "check_pending"; accessExpiresAt = now;
        return accessClaim;
    }
    public boolean ownsAccessClaim(UUID claim, long expectedGeneration, Instant now) {
        return claim != null && claim.equals(accessClaim) && generation == expectedGeneration && accessClaimUntil.isAfter(now);
    }
    public void grant(long accountVersion, String name, String url, boolean privateRepo, Instant now) {
        accessAccountVersion = accountVersion; fullName = name; htmlUrl = url; privateRepository = privateRepo;
        accessStatus = "verified"; accessExpiresAt = now.plusSeconds(60); accessClaim = null; accessClaimUntil = null;
    }
    public void deny(String reason, Instant now) {
        accessStatus = reason; accessExpiresAt = now; generation = Math.incrementExact(generation); updatedAt = now;
        accessClaim = null; accessClaimUntil = null;
    }
}
