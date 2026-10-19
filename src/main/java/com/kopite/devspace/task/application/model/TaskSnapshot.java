package com.kopite.devspace.task.application.model;
import com.kopite.devspace.task.application.exception.TaskNotFoundException;
import com.kopite.devspace.task.domain.Task;
import com.kopite.devspace.project.domain.Project;
import java.time.Instant;
import java.util.UUID;
public record TaskSnapshot(UUID id, long revision, Instant createdAt, Instant updatedAt, String title,
    UUID projectId, String projectName, UUID categoryId, String description, String status, String priority,
    String tag, Instant deletedAt, @com.fasterxml.jackson.annotation.JsonIgnore Long dataRevision, @io.swagger.v3.oas.annotations.media.Schema(types={"object","null"}) TaskSourceSnapshot source) {
    public TaskSnapshot(UUID id, long revision, Instant createdAt, Instant updatedAt, String title,
    UUID projectId, String projectName, UUID categoryId, String description, String status, String priority,
    String tag, Instant deletedAt, Long dataRevision) { this(id,revision,createdAt,updatedAt,title,projectId,projectName,categoryId,description,status,priority,tag,deletedAt,dataRevision,null); }
    public TaskSnapshot(UUID id, long revision, Instant createdAt, Instant updatedAt, String title,
    UUID projectId, String projectName, UUID categoryId, String description, String status, String priority,
    String tag, Instant deletedAt) { this(id, revision, createdAt, updatedAt, title, projectId, projectName, categoryId, description, status, priority, tag, deletedAt, null); }
    public TaskSnapshot observed(long workspaceRevision) { return new TaskSnapshot(id, revision, createdAt, updatedAt, title, projectId, projectName, categoryId, description, status, priority, tag, deletedAt, workspaceRevision,source); }
    public TaskSnapshot withSource(TaskSourceSnapshot value) { return new TaskSnapshot(id,revision,createdAt,updatedAt,title,projectId,projectName,categoryId,description,status,priority,tag,deletedAt,dataRevision,value); }
    public static TaskSnapshot from(Task task, Project project) {
        if (!task.getWorkspaceId().equals(project.getWorkspaceId()) || !task.getProjectId().equals(project.getId()))
            throw new TaskNotFoundException();
        return new TaskSnapshot(task.getId(), task.getRevision(), task.getCreatedAt(), task.getUpdatedAt(),
            task.getTitle(), project.getId(), project.getName(), project.getCategoryId(), task.getDescription(),
            task.getStatus(), task.getPriority(), task.getTag(), task.getDeletedAt());
    }
}
