package com.kopite.devspace.journal.application.command;
import com.kopite.devspace.journal.application.exception.JournalNotFoundException;
import com.kopite.devspace.journal.application.model.JournalSnapshot;
import com.kopite.devspace.journal.application.port.JournalCreateReplayStore;
import com.kopite.devspace.journal.domain.Journal;
import com.kopite.devspace.journal.domain.JournalConflictException;
import com.kopite.devspace.journal.domain.JournalRepository;
import com.kopite.devspace.journal.domain.JournalValidationException;
import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.project.domain.Project;
import com.kopite.devspace.project.domain.ProjectRepository;
import com.kopite.devspace.workspace.domain.PersonalWorkspace;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class JournalCommandService {
    private final com.kopite.devspace.document.application.DocumentDeletionService documentDeletion;
    private final CurrentUserService currentUser;
    private final PersonalWorkspaceRepository workspaces;
    private final ProjectRepository projects;
    private final JournalRepository journals;
    private final JournalCreateReplayStore replays;
    private final Clock projectClock;
    private final com.kopite.devspace.documentspace.domain.DocumentSpaceRepository documentSpaces;

    @Transactional
    public JournalSnapshot create(UUID userId,String key,CreateJournalCommand command) {
        var workspace=lockWorkspace(userId);
        if(key==null || !key.matches("[!-~]{1,128}"))
            throw new JournalValidationException("Idempotency-Key","must contain 1 to 128 visible ASCII characters");
        var values=command.values();
        Project project=projects.lockOwned(workspace.getId(),values.projectId()).orElseThrow(JournalNotFoundException::new);
        var now=projectClock.instant().truncatedTo(ChronoUnit.MICROS);
        String hash=JournalRequestHash.of(command);
        var previous=replays.find(workspace.getId(),key);
        if(previous.isPresent() && previous.get().expiresAt().isAfter(now)) {
            var saved=previous.get();
            if(saved.legacy()) throw new JournalConflictException("IDEMPOTENCY_KEY_REUSED");
            projects.findOwned(workspace.getId(),saved.result().projectId()).orElseThrow(JournalNotFoundException::new);
            journals.findOwned(workspace.getId(),saved.result().id()).ifPresent(journal ->
                projects.findOwned(workspace.getId(),journal.getProjectId()).orElseThrow(JournalNotFoundException::new));
            if(!saved.requestHash().equals(hash)) throw new JournalConflictException("IDEMPOTENCY_KEY_REUSED");
            return saved.result();
        }
        requireActive(project);
        replays.removeExpired(workspace.getId(),now);
        var space=com.kopite.devspace.documentspace.domain.DocumentSpace.create(now);
        documentSpaces.insert(space);
        documentSpaces.flush();
        Journal journal=journals.save(Journal.create(workspace.getId(),space.getId(),values,now));
        workspace.recordBusinessMutation();
        var result=JournalSnapshot.from(journal,project).observed(workspace.getDataRevision());
        replays.save(workspace.getId(),key,hash,result,now);
        return result;
    }
    @Transactional
    public JournalSnapshot update(UUID userId,UUID id,UpdateJournalCommand command) {
        var workspace=lockWorkspace(userId);
        var journal=lockJournal(workspace.getId(),id,command.projectId());
        journal.checkRevision(command.revision());
        var values=command.applyTo(journal.values());
        var target=projects.findOwned(workspace.getId(),values.projectId()).orElseThrow(JournalNotFoundException::new);
        if(!target.getId().equals(journal.getProjectId())) requireActive(target);
        journal.update(command.revision(),values,projectClock.instant());
        workspace.recordBusinessMutation();
        return JournalSnapshot.from(journal,target).observed(workspace.getDataRevision());
    }
    @Transactional
    public UUID delete(UUID userId,UUID id,long revision) {
        var workspace=lockWorkspace(userId);
        var journal=lockJournal(workspace.getId(),id,null);
        journal.checkRevision(revision);
        var space=documentSpaces.find(journal.getDocumentSpaceId(),true).orElseThrow(JournalNotFoundException::new);
        documentDeletion.deleteSpaceChildren(space.getId());
        journals.delete(journal);
        journals.flush();
        documentSpaces.delete(space);
        workspace.recordBusinessMutation();
        return id;
    }
    private Journal lockJournal(UUID workspace,UUID id,UUID target) {
        var discovered=journals.findOwned(workspace,id).orElseThrow(JournalNotFoundException::new);
        Stream.of(discovered.getProjectId(),target==null?discovered.getProjectId():target).distinct().sorted()
            .forEach(project->projects.lockOwned(workspace,project).orElseThrow(JournalNotFoundException::new));
        return journals.lockOwned(workspace,id).orElseThrow(JournalNotFoundException::new);
    }
    private PersonalWorkspace lockWorkspace(UUID userId) {
        var workspace=workspaces.lockByOwnerId(userId).orElseThrow(JournalNotFoundException::new);
        currentUser.resolve(userId);
        return workspace;
    }
    private void requireActive(Project project) {
        if(!"active".equals(project.getStatus())) throw new JournalConflictException("PROJECT_ARCHIVED");
    }
    @Transactional
    public com.kopite.devspace.workspace.application.DeletedResource deleteObserved(UUID user,UUID id,long revision) {
        var deleted=delete(user,id,revision);
        return new com.kopite.devspace.workspace.application.DeletedResource(deleted,currentUser.resolve(user).workspace().getDataRevision());
    }

}
