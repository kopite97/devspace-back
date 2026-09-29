package com.kopite.devspace.journal.infrastructure;

import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.document.domain.DocumentException;
import com.kopite.devspace.documentspace.application.DocumentSpaceAccess;
import com.kopite.devspace.journal.domain.*;
import com.kopite.devspace.project.domain.ProjectRepository;
import com.kopite.devspace.workspace.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component @RequiredArgsConstructor
public class JournalDocumentSpaceAccess implements DocumentSpaceAccess {
    private final CurrentUserService users;
    private final PersonalWorkspaceRepository workspaces;
    private final JournalRepository journals;
    private final ProjectRepository projects;
    private final jakarta.persistence.EntityManager em;

    public PersonalWorkspace read(UUID user,UUID space) {
        var workspace=users.resolve(user).workspace();
        var journal=journals.findByDocumentSpace(space).orElseThrow(DocumentException::missing);
        check(workspace,journal);
        projects.findOwned(workspace.getId(),journal.getProjectId()).orElseThrow(DocumentException::missing);
        return workspace;
    }
    public PersonalWorkspace lock(UUID user,UUID space) {
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(DocumentException::missing);
        users.resolve(user);
        return lockJournal(workspace,space);
    }
    public PersonalWorkspace maintenance(UUID space) {
        var owner=em.createQuery("select w.owner.id from PersonalWorkspace w where w.id in (select j.workspaceId from Journal j where j.documentSpaceId=:space)",UUID.class)
            .setParameter("space",space).getResultStream().findFirst().orElseThrow(DocumentException::missing);
        return lockJournal(workspaces.lockByOwnerId(owner).orElseThrow(DocumentException::missing),space);
    }
    private PersonalWorkspace lockJournal(PersonalWorkspace workspace,UUID space) {
        var discovered=journals.findByDocumentSpace(space).orElseThrow(DocumentException::missing);check(workspace,discovered);
        projects.lockOwned(workspace.getId(),discovered.getProjectId()).orElseThrow(DocumentException::missing);
        var locked=journals.lockOwned(workspace.getId(),discovered.getId()).orElseThrow(DocumentException::missing);
        if(!locked.getDocumentSpaceId().equals(space)||!locked.getProjectId().equals(discovered.getProjectId()))throw DocumentException.missing();
        return workspace;
    }
    private void check(PersonalWorkspace workspace,Journal journal) {
        if(!workspace.getId().equals(journal.getWorkspaceId()))throw DocumentException.missing();
    }
}
