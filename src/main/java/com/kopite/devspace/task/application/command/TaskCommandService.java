package com.kopite.devspace.task.application.command;
import com.kopite.devspace.task.application.exception.TaskNotFoundException;
import com.kopite.devspace.task.application.model.TaskSnapshot;
import com.kopite.devspace.task.application.port.TaskCreateReplayStore;
import com.kopite.devspace.task.domain.Task;
import com.kopite.devspace.task.domain.TaskConflictException;
import com.kopite.devspace.task.domain.TaskRepository;
import com.kopite.devspace.task.domain.TaskValidationException;

import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.project.domain.Project;
import com.kopite.devspace.project.domain.ProjectRepository;
import com.kopite.devspace.workspace.domain.PersonalWorkspace;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class TaskCommandService {
    private final CurrentUserService currentUser;
    private final PersonalWorkspaceRepository workspaces;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final TaskCreateReplayStore replays;
    private final Clock projectClock;
    private final com.kopite.devspace.task.application.port.TaskSourceAccess sources;

    @Transactional
    public TaskSnapshot create(UUID userId, String key, CreateTaskCommand command) {
        var workspace = lockWorkspace(userId);
        if (key == null || !key.matches("[!-~]{1,128}"))
            throw new TaskValidationException("Idempotency-Key", "must contain 1 to 128 visible ASCII characters");
        var values = command.values();
        Project project = projects.lockOwned(workspace.getId(), values.projectId()).orElseThrow(TaskNotFoundException::new);
        Instant now = projectClock.instant().truncatedTo(ChronoUnit.MICROS);
        String hash = TaskRequestHash.of(command);
        var previous = replays.find(workspace.getId(), key);
        if (previous.isPresent() && previous.get().expiresAt().isAfter(now)) {
            var saved = previous.get();
            if(saved.legacy()) throw new TaskConflictException("IDEMPOTENCY_KEY_REUSED");
            Project originalProject = projects.findOwned(workspace.getId(), saved.result().projectId()).orElseThrow(TaskNotFoundException::new);
            Task original = tasks.findOwned(workspace.getId(), saved.result().id()).orElseThrow(TaskNotFoundException::new);
            if (original.isExternallyManaged()) sources.requireAccessible(workspace.getId(), original.getId());
            projects.findOwned(workspace.getId(), original.getProjectId()).orElseThrow(TaskNotFoundException::new);
            if (!saved.requestHash().equals(hash)) throw new TaskConflictException("IDEMPOTENCY_KEY_REUSED");
            return saved.result();
        }
        requireActive(project);
        replays.removeExpired(workspace.getId(), now);
        Task task = tasks.save(Task.create(workspace.getId(), values, now));
        workspace.recordBusinessMutation();
        var result = TaskSnapshot.from(task, project).observed(workspace.getDataRevision());
        replays.save(workspace.getId(), key, hash, result, now);
        return result;
    }

    @Transactional
    public TaskSnapshot update(UUID userId, UUID id, UpdateTaskCommand command) {
        var workspace = lockWorkspace(userId);
        Task task = lockTask(workspace.getId(), id, command.projectId());
        var source = sources.requireAccessible(workspace.getId(), id);
        if (task.isExternallyManaged() && (command.title()!=null || command.description()!=null || command.status()!=null || command.projectId()!=null))
            throw new TaskConflictException("TASK_SOURCE_MANAGED");
        task.checkRevision(command.revision());
        if (task.getDeletedAt() != null) throw new TaskConflictException("RESOURCE_DELETED");
        var values = command.applyTo(task.values());
        Project target = projects.findOwned(workspace.getId(), values.projectId()).orElseThrow(TaskNotFoundException::new);
        if (!task.getProjectId().equals(target.getId())) requireActive(target);
        task.update(command.revision(), values, projectClock.instant());
        workspace.recordBusinessMutation();
        return TaskSnapshot.from(task, target).withSource(source).observed(workspace.getDataRevision());
    }

    @Transactional
    public TaskSnapshot delete(UUID userId, UUID id, long revision) {
        var workspace = lockWorkspace(userId);
        Task task = lockTask(workspace.getId(), id, null);
        var source = sources.requireAccessible(workspace.getId(), id);
        task.delete(revision, projectClock.instant());
        sources.deleted(workspace.getId(), id);
        workspace.recordBusinessMutation();
        return snapshot(workspace.getId(), task).withSource(source).observed(workspace.getDataRevision());
    }

    @Transactional
    public TaskSnapshot restore(UUID userId, UUID id, long revision) {
        var workspace = lockWorkspace(userId);
        Task task = lockTask(workspace.getId(), id, null);
        var source = sources.requireAccessible(workspace.getId(), id);
        task.restore(revision, projectClock.instant());
        sources.restored(workspace.getId(), id);
        workspace.recordBusinessMutation();
        return snapshot(workspace.getId(), task).withSource(source).observed(workspace.getDataRevision());
    }

    private TaskSnapshot snapshot(UUID workspace, Task task) {
        return TaskSnapshot.from(task, projects.findOwned(workspace, task.getProjectId()).orElseThrow(TaskNotFoundException::new));
    }

    private Task lockTask(UUID workspace, UUID id, UUID target) {
        Task discovered = tasks.findOwned(workspace, id).orElseThrow(TaskNotFoundException::new);
        Stream.of(discovered.getProjectId(), target == null ? discovered.getProjectId() : target)
            .distinct().sorted().forEach(project -> projects.lockOwned(workspace, project).orElseThrow(TaskNotFoundException::new));
        return tasks.lockOwned(workspace, id).orElseThrow(TaskNotFoundException::new);
    }

    private PersonalWorkspace lockWorkspace(UUID userId) {
        var workspace = workspaces.lockByOwnerId(userId).orElseThrow(TaskNotFoundException::new);
        currentUser.resolve(userId);
        return workspace;
    }
    private void requireActive(Project project) {
        if (!"active".equals(project.getStatus())) throw new TaskConflictException("PROJECT_ARCHIVED");
    }
}
