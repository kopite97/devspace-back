package com.kopite.devspace.task.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task {
    public static final long MAX_REVISION = 9007199254740991L;
    @Id
    private UUID id;
    @Column(nullable = false, updatable = false)
    private UUID workspaceId;
    @Column(nullable = false)
    private UUID projectId;
    @Column(nullable = false, columnDefinition = "text")
    private String title;
    @Column(nullable = false, columnDefinition = "text")
    private String description;
    @Column(nullable = false, columnDefinition = "text")
    private String status;
    @Column(nullable = false, columnDefinition = "text")
    private String priority;
    @Column(nullable = false, columnDefinition = "text")
    private String tag;
    @Column(nullable = false)
    private long revision;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    private Instant deletedAt;
    @Column(nullable = false, columnDefinition = "text")
    private String sourceType = "local";

    public static Task create(UUID workspaceId, TaskValues values, Instant now) {
        Task task = new Task();
        task.id = UUID.randomUUID();
        task.workspaceId = Objects.requireNonNull(workspaceId);
        task.assign(values);
        task.revision = 1;
        task.createdAt = timestamp(now);
        task.updatedAt = task.createdAt;
        return task;
    }

    public void checkRevision(long expected) {
        if (expected < 1 || expected > MAX_REVISION)
            throw new TaskValidationException("revision", "must be a positive safe integer");
        if (expected != revision) throw new TaskConflictException("REVISION_CONFLICT");
    }

    public void update(long expected, TaskValues values, Instant now) {
        checkRevision(expected);
        if (deletedAt != null) throw new TaskConflictException("RESOURCE_DELETED");
        Objects.requireNonNull(values);
        if (isExternallyManaged() && (!title.equals(values.title()) || !description.equals(values.description())
                || !status.equals(values.status()) || !projectId.equals(values.projectId()))) throw new TaskConflictException("TASK_SOURCE_MANAGED");
        Instant time = prepareMutation(now);
        assign(values);
        advance(time);
    }

    public void delete(long expected, Instant now) {
        checkRevision(expected);
        if (deletedAt != null) throw new TaskConflictException("INVALID_RESOURCE_STATE");
        Instant time = prepareMutation(now);
        deletedAt = time;
        advance(time);
    }

    public void restore(long expected, Instant now) {
        checkRevision(expected);
        if (deletedAt == null) throw new TaskConflictException("INVALID_RESOURCE_STATE");
        Instant time = prepareMutation(now);
        deletedAt = null;
        advance(time);
    }

    public TaskValues values() {
        return new TaskValues(title, projectId, description, status, priority, tag);
    }
    public boolean isExternallyManaged() { return "github".equals(sourceType); }
    public static Task fromGitHub(UUID workspaceId, TaskValues values, Instant now) {
        Task task = create(workspaceId, values, now); task.sourceType = "github"; return task;
    }
    public boolean synchronizeSource(String title, String description, String status, boolean metadataChanged, Instant now) {
        if (!isExternallyManaged() || deletedAt != null) return false;
        var values = new TaskValues(title, projectId, description, status, priority, tag);
        if (!metadataChanged && values.equals(values())) return false;
        Instant time = prepareMutation(now); assign(values); advance(time); return true;
    }
    public void convertToLocal(long expected, Instant now) {
        checkRevision(expected);
        if (!isExternallyManaged()) throw new TaskConflictException("INVALID_RESOURCE_STATE");
        Instant time = prepareMutation(now); sourceType = "local"; advance(time);
    }

    private Instant prepareMutation(Instant now) {
        if (revision == MAX_REVISION) throw new TaskConflictException("REVISION_CONFLICT");
        return timestamp(now);
    }
    private static Instant timestamp(Instant now) {
        return Objects.requireNonNull(now).truncatedTo(ChronoUnit.MICROS);
    }
    private void advance(Instant now) {
        revision++;
        updatedAt = now;
    }
    private void assign(TaskValues values) {
        Objects.requireNonNull(values);
        title = values.title();
        projectId = values.projectId();
        description = values.description();
        status = values.status();
        priority = values.priority();
        tag = values.tag();
    }
}
