package com.kopite.devspace.compatibility.application;
import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.project.domain.*;
import com.kopite.devspace.project.application.exception.*;
import com.kopite.devspace.task.domain.TaskRepository;
import com.kopite.devspace.journal.domain.JournalRepository;
import com.kopite.devspace.milestone.domain.MilestoneRepository;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

/** Historical bridge concurrency fixture; explicitly imported by its test only. */
@RequiredArgsConstructor
public class LegacyReplayService {
    private final CurrentUserService currentUser;
    private final PersonalWorkspaceRepository workspaces;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final JournalRepository journals;
    private final MilestoneRepository milestones;
    private final LegacyReplayStore replays;
    private final Clock projectClock;
    @Transactional
    public LegacyReplayStore.Replay replay(UUID user,String key,LegacyCreateRequest request) {
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(ProjectNotFoundException::new);
        currentUser.resolve(user);
        if(key==null||!key.matches("[!-~]{1,128}"))throw new ProjectValidationException("Idempotency-Key","must contain 1 to 128 visible ASCII characters");
        UUID w=workspace.getId();
        if(request.projectId()!=null)projects.lockOwned(w,request.projectId()).orElseThrow(ProjectNotFoundException::new);
        var saved=replays.find(w,request.resource(),key).filter(r->r.expiresAt().isAfter(projectClock.instant())).orElseThrow(ApiVersionRetiredException::new);
        if(!saved.legacy())throw new ProjectIdempotencyConflictException();
        switch(request.resource()) {
            case TASK -> {
                projects.findOwned(w,saved.projectId()).orElseThrow(ProjectNotFoundException::new);
                var task=tasks.findOwned(w,saved.id()).orElseThrow(ProjectNotFoundException::new);
                projects.findOwned(w,task.getProjectId()).orElseThrow(ProjectNotFoundException::new);
            }
            case JOURNAL -> {
                projects.findOwned(w,saved.projectId()).orElseThrow(ProjectNotFoundException::new);
                journals.findOwned(w,saved.id()).ifPresent(j->projects.findOwned(w,j.getProjectId()).orElseThrow(ProjectNotFoundException::new));
            }
            case MILESTONE -> {
                projects.findOwned(w,saved.projectId()).orElseThrow(ProjectNotFoundException::new);
                milestones.findOwned(w,saved.id()).ifPresent(m->projects.findOwned(w,m.getProjectId()).orElseThrow(ProjectNotFoundException::new));
            }
            default -> { }
        }
        if(!saved.hash().equals(request.hash()))throw new ProjectIdempotencyConflictException();
        return saved;
    }
}
