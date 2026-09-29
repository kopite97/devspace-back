package com.kopite.devspace.document.application;
import com.kopite.devspace.attachment.application.*;
import com.kopite.devspace.document.domain.*;
import com.kopite.devspace.documentspace.application.DocumentSpaceAccess;
import com.kopite.devspace.documentspace.domain.DocumentSpaceRepository;
import com.kopite.devspace.workspace.application.DeletedResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.time.Clock;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class DocumentDeletionService {
    private final DocumentSpaceAccess access;private final DocumentSpaceRepository spaces;private final DocumentRepository documents;
    private final AttachmentCleanupStore cleanup;private final AttachmentCleanupDispatch dispatch;private final Clock clock;
    @Transactional public DeletedResource delete(UUID user,UUID id) {
        var space=documents.spaceOf(id).orElseThrow(DocumentException::missing);var workspace=access.lock(user,space);
        var parent=spaces.find(space,true).orElseThrow(DocumentException::missing);
        documents.find(id,true).orElseThrow(DocumentException::missing);
        cleanup.purgeDocument(id,clock.instant());parent.collectionChanged(clock.instant());
        if(workspace.getDataRevision()==Long.MAX_VALUE)throw new DocumentException("REVISION_CONFLICT",null);
        workspace.recordBusinessMutation();afterCommit();return new DeletedResource(id,workspace.getDataRevision());
    }
    @Transactional(propagation=Propagation.MANDATORY) public void deleteSpaceChildren(UUID space){cleanup.purgeSpace(space,clock.instant());afterCommit();}
    private void afterCommit(){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCommit(){dispatch.submit();}});}
}
