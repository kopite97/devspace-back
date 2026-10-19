package com.kopite.devspace.task.application.command;

import com.kopite.devspace.task.domain.*;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import com.kopite.devspace.project.domain.ProjectRepository;
import com.kopite.devspace.task.application.exception.TaskNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.UUID;

/** Called after the source use case has locked workspace/project and validated current access. */
@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class TaskSourceCommands {
    private final TaskRepository tasks;
    private final PersonalWorkspaceRepository workspaces;
    private final ProjectRepository projects;
    public UUID create(UUID user, UUID project, String title, String body, String status, Instant now) {
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(TaskNotFoundException::new);
        var target=projects.lockOwned(workspace.getId(),project).orElseThrow(TaskNotFoundException::new);
        if (!target.getStatus().equals("active")) throw new TaskConflictException("PROJECT_ARCHIVED");
        var task=tasks.save(Task.fromGitHub(workspace.getId(),new TaskValues(title,project,body,status,"normal",""),now));
        workspace.recordBusinessMutation(); return task.getId();
    }
    public boolean synchronize(UUID user, UUID taskId, String title, String body, String status, boolean metadataChanged, Instant now) {
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(TaskNotFoundException::new);
        var task=tasks.lockOwned(workspace.getId(),taskId).orElseThrow(TaskNotFoundException::new);
        boolean changed=task.synchronizeSource(title,body,status,metadataChanged,now);
        if(changed) workspace.recordBusinessMutation(); return changed;
    }
    public void convert(UUID user,UUID taskId,long revision,Instant now) {
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(TaskNotFoundException::new);
        var task=tasks.lockOwned(workspace.getId(),taskId).orElseThrow(TaskNotFoundException::new);
        task.convertToLocal(revision,now); workspace.recordBusinessMutation();
    }
    public void purge(UUID user,UUID taskId) {
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(TaskNotFoundException::new);
        var task=tasks.lockOwned(workspace.getId(),taskId).orElseThrow(TaskNotFoundException::new);
        if(!task.isExternallyManaged())throw new TaskConflictException("INVALID_RESOURCE_STATE");
        tasks.remove(task);workspace.recordBusinessMutation();
    }
}
