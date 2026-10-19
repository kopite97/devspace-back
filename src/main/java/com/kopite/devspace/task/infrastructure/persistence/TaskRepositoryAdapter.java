package com.kopite.devspace.task.infrastructure.persistence;

import com.kopite.devspace.task.domain.Task;
import com.kopite.devspace.task.domain.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TaskRepositoryAdapter implements TaskRepository {
    private final TaskJpaRepository repository;

    public Task save(Task task) { return repository.save(task); }
    public void remove(Task task) { repository.delete(task); }
    public Optional<Task> findOwned(UUID workspaceId, UUID id) {
        return repository.findByWorkspaceIdAndId(workspaceId, id);
    }
    public Optional<Task> lockOwned(UUID workspaceId, UUID id) {
        return repository.lockOwned(workspaceId, id);
    }
}
