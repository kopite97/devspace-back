package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.model.GitHubLinkSnapshot;
import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.*;
import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.project.domain.ProjectRepository;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import jakarta.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.Clock;
import java.util.*;

@Repository @Transactional
public class GitHubLinkPersistence implements GitHubLinkStore {
    private final EntityManager em;
    private final CurrentUserService users;
    private final ProjectRepository projects;
    private final PersonalWorkspaceRepository workspaces;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final GitHubSyncStore sync;
    public GitHubLinkPersistence(EntityManager em, CurrentUserService users, ProjectRepository projects,
            PersonalWorkspaceRepository workspaces, JdbcTemplate jdbc, Clock projectClock, GitHubSyncStore sync) {
        this.em = em; this.users = users; this.projects = projects; this.workspaces = workspaces; this.jdbc = jdbc; this.clock = projectClock;
        this.sync = sync;
    }
    @Override @Transactional(readOnly = true) public void requireProject(UUID user, UUID project, boolean active) {
        UUID workspace = users.resolve(user).workspace().getId();
        var value = projects.findOwned(workspace, project).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        if (active && !value.getStatus().equals("active")) throw new GitHubException("PROJECT_ARCHIVED");
    }
    @Override @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) public List<GitHubLinkSnapshot> list(UUID user, UUID project) {
        requireProject(user, project, false);
        UUID workspace = users.resolve(user).workspace().getId();
        var account = em.find(GitHubAccount.class, user); Instant now = clock.instant();
        return em.createQuery("select l from GitHubRepositoryLink l where l.workspaceId=:w and l.projectId=:p and l.status<>'disconnected' order by l.createdAt,l.id", GitHubRepositoryLink.class)
                .setParameter("w", workspace).setParameter("p", project).setMaxResults(100).getResultList().stream().map(l -> visible(l, account, now)).toList();
    }
    @Override @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) public GitHubLinkSnapshot get(UUID user, UUID project, UUID id) {
        requireProject(user, project, false);
        var link = em.find(GitHubRepositoryLink.class, id);
        if (link == null || !link.getUserId().equals(user) || !link.getProjectId().equals(project)) throw new GitHubException("RESOURCE_NOT_FOUND");
        return visible(link, em.find(GitHubAccount.class, user), clock.instant());
    }
    private static GitHubLinkSnapshot visible(GitHubRepositoryLink link, GitHubAccount account, Instant now) {
        var value = GitHubLinkSnapshot.from(link);
        if (account != null && account.getStatus().equals("connected") && account.getGithubUserId() == link.getGithubUserId()
                && link.accessible(account.getTokenVersion(), now)) return value;
        String reason = account == null || !account.getStatus().equals("connected") ? "reauthorization_required"
                : value.accessStatus().equals("verified") ? "check_pending" : value.accessStatus();
        return new GitHubLinkSnapshot(value.id(), value.projectId(), value.installationId(), value.repositoryId(), null, null,
                value.privateRepository(), value.status(), reason, value.paused(), value.revision(), value.accessExpiresAt(), value.createdAt(), value.updatedAt());
    }
    @Override public GitHubLinkSnapshot connect(UUID user, UUID project, long githubUser, long accountVersion, long installation, long installationVersion,
            GitHubRepositoryClient.Repository repository, String key, Instant now) {
        if (key == null || !key.matches("[!-~]{1,128}")) throw new GitHubException("VALIDATION_ERROR");
        var workspace = workspaces.lockByOwnerId(user).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        users.resolve(user);
        var target = projects.lockOwned(workspace.getId(), project).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        var account = em.find(GitHubAccount.class, user, LockModeType.PESSIMISTIC_WRITE);
        if (account == null || !account.getStatus().equals("connected") || account.getGithubUserId() != githubUser || account.getTokenVersion() != accountVersion)
            throw new GitHubException("GITHUB_REAUTHORIZATION_REQUIRED");
        Long currentInstallationVersion = jdbc.queryForObject("select generation from github_installation_epochs where installation_id=? for update", Long.class, installation);
        if (currentInstallationVersion == null || currentInstallationVersion != installationVersion) throw new GitHubException("GITHUB_ACCESS_DENIED");
        jdbc.update("delete from github_link_create_replays where user_id=? and project_id=? and expires_at<=?", user, project, java.sql.Timestamp.from(now));
        var replay = jdbc.queryForList("select installation_id,repository_id,link_id from github_link_create_replays where user_id=? and project_id=? and request_key=?", user, project, key);
        if (!replay.isEmpty()) {
            var row = replay.getFirst();
            if (((Number)row.get("installation_id")).longValue() != installation || ((Number)row.get("repository_id")).longValue() != repository.id())
                throw new GitHubException("IDEMPOTENCY_KEY_REUSED");
            var existing = em.find(GitHubRepositoryLink.class, row.get("link_id"), LockModeType.PESSIMISTIC_WRITE);
            if (existing.getStatus().equals("disconnected") || existing.getStatus().equals("disconnecting")) throw new GitHubException("GITHUB_LINK_INACTIVE");
            existing.grant(accountVersion, repository.fullName(), repository.htmlUrl(), repository.privateRepository(), now);
            account.changeAccessScope();
            return GitHubLinkSnapshot.from(existing);
        }
        if (!target.getStatus().equals("active")) throw new GitHubException("PROJECT_ARCHIVED");
        long count = em.createQuery("select count(l) from GitHubRepositoryLink l where l.workspaceId=:w and l.projectId=:p and l.status<>'disconnected'", Long.class)
                .setParameter("w", workspace.getId()).setParameter("p", project).getSingleResult();
        if (count >= 100) throw new GitHubException("GITHUB_LINK_LIMIT_EXCEEDED");
        var previous = em.createQuery("select l from GitHubRepositoryLink l where l.workspaceId=:w and l.projectId=:p and l.repositoryId=:r", GitHubRepositoryLink.class)
                .setParameter("w", workspace.getId()).setParameter("p", project).setParameter("r", repository.id()).getResultList();
        GitHubRepositoryLink link;
        if(previous.isEmpty())link=GitHubRepositoryLink.connect(workspace.getId(), project, user, githubUser, installation,
                repository.id(), repository.fullName(), repository.htmlUrl(), repository.privateRepository(), now);
        else{link=previous.getFirst();link.reconnect(githubUser,installation,repository.fullName(),repository.htmlUrl(),repository.privateRepository(),now);}
        link.grant(accountVersion, repository.fullName(), repository.htmlUrl(), repository.privateRepository(), now);
        account.changeAccessScope();
        em.persist(link); em.flush();
        jdbc.update("insert into github_import_settings(link_id) values(?) on conflict(link_id) do update set enabled=false,generation=github_import_settings.generation+1,baseline_ready=false,label_ids='[]',assignee_ids='[]',todo_label_id=null,doing_label_id=null,done_label_id=null",link.getId());
        jdbc.update("update github_repository_links set last_manual_sync_at=null,last_successful_sync_at=null where id=?",link.getId());
        sync.enqueue(link.getId(), now);
        jdbc.update("insert into github_link_create_replays(user_id,project_id,request_key,installation_id,repository_id,link_id,expires_at) values(?,?,?,?,?,?,?)",
                user, project, key, installation, repository.id(), link.getId(), java.sql.Timestamp.from(now.plusSeconds(86400)));
        workspace.recordBusinessMutation();
        return GitHubLinkSnapshot.from(link);
    }
}
