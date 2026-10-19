package com.kopite.devspace.project.application.command;
import com.kopite.devspace.project.application.exception.ProjectIdempotencyConflictException;
import com.kopite.devspace.project.application.exception.ProjectNotFoundException;
import com.kopite.devspace.project.application.model.ProjectSnapshot;
import com.kopite.devspace.project.application.port.ProjectCreateReplayStore;

import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.project.domain.Project;
import com.kopite.devspace.project.domain.ProjectRepository;
import com.kopite.devspace.project.domain.ProjectValidationException;
import com.kopite.devspace.workspace.domain.PersonalWorkspace;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectCommandService {
    private final CurrentUserService currentUser;
    private final PersonalWorkspaceRepository workspaces;
    private final ProjectRepository projects;
    private final ProjectCreateReplayStore replays;
    private final Clock projectClock;
    private final com.kopite.devspace.projectcategory.domain.ProjectCategoryRepository categories;
    private final com.kopite.devspace.project.application.port.ProjectExternalLifecycle externalLifecycle;

    @Transactional
    public ProjectSnapshot create(UUID userId, String key, CreateProjectCommand command) {
        var workspace = lockWorkspace(userId);
        if (key == null || !key.matches("[!-~]{1,128}")) {
            throw new ProjectValidationException("Idempotency-Key", "must contain 1 to 128 visible ASCII characters");
        }
        var values = command.values();
        String requestHash = ProjectRequestHash.of(command);
        Instant now = projectClock.instant().truncatedTo(ChronoUnit.MICROS);
        var previous = replays.find(workspace.getId(), key);
        if (previous.isPresent() && previous.get().expiresAt().isAfter(now)) {
            if (previous.get().legacy() || !previous.get().requestHash().equals(requestHash)) throw new ProjectIdempotencyConflictException();
            return previous.get().result();
        }
        validateCategory(workspace.getId(),values.categoryId());
        capacity(workspace);
        replays.removeExpired(workspace.getId(), now);
        Project project = projects.save(Project.create(workspace.getId(), values, now));
        workspace.recordBusinessMutation();
        var result = ProjectSnapshot.from(project).observed(workspace.getDataRevision());
        replays.save(workspace.getId(), key, requestHash, result, now);
        return result;
    }

    @Transactional
    public ProjectSnapshot update(UUID userId, UUID id, UpdateProjectCommand command) {
        var workspace = lockWorkspace(userId);
        var project = projects.lockOwned(workspace.getId(), id).orElseThrow(ProjectNotFoundException::new);
        project.checkRevision(command.revision());
        if(command.category().present()) validateCategory(workspace.getId(),command.category().id());
        capacity(workspace);
        String previousStatus=project.getStatus();
        project.update(command.revision(), command.applyTo(project.values()),
                command.status() == null ? project.getStatus() : command.status(), projectClock.instant());
        externalLifecycle.statusChanged(workspace.getId(),id,previousStatus,project.getStatus(),projectClock.instant());
        workspace.recordBusinessMutation();
        return ProjectSnapshot.from(project).observed(workspace.getDataRevision());
    }

    private PersonalWorkspace lockWorkspace(UUID userId) {
        // Lock before loading this workspace through CurrentUserService so queued writers see its latest counter.
        var workspace = workspaces.lockByOwnerId(userId).orElseThrow(ProjectNotFoundException::new);
        currentUser.resolve(userId);
        return workspace;
    }
    private void validateCategory(UUID workspace,UUID category) {
        if(category!=null) categories.lockOwned(workspace,category).orElseThrow(ProjectNotFoundException::new);
    }
    private void capacity(PersonalWorkspace workspace) {
        if(workspace.getDataRevision()==Long.MAX_VALUE) throw new com.kopite.devspace.project.domain.ProjectRevisionConflictException();
    }
}
