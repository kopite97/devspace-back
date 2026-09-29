package com.kopite.devspace.journal.application;
import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.document.application.*;
import com.kopite.devspace.document.domain.DocumentException;
import com.kopite.devspace.journal.domain.JournalRepository;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class JournalDocumentCollectionService {
    private final CurrentUserService users;private final PersonalWorkspaceRepository workspaces;private final JournalRepository journals;
    private final DocumentCommandService commands;private final DocumentQueryService queries;
    @Transactional public DocumentSnapshot create(UUID user,UUID journal,String title,String content) {
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(DocumentException::missing);users.resolve(user);
        var parent=journals.findOwned(workspace.getId(),journal).orElseThrow(DocumentException::missing);
        return commands.create(user,parent.getDocumentSpaceId(),title,content);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DocumentQueryService.Page list(UUID user,UUID journal,int limit,String cursor) {
        var workspace=users.resolve(user).workspace();var parent=journals.findOwned(workspace.getId(),journal).orElseThrow(DocumentException::missing);
        return queries.list(user,parent.getDocumentSpaceId(),journal.toString(),limit,cursor);
    }
}
