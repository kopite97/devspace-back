package com.kopite.devspace.attachment.application;
import com.kopite.devspace.attachment.domain.*;
import com.kopite.devspace.document.domain.*;
import com.kopite.devspace.documentspace.application.DocumentSpaceAccess;
import com.kopite.devspace.documentspace.domain.DocumentSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
@Service @RequiredArgsConstructor
public class UploadMetadataService {
    private final DocumentSpaceAccess access;
    private final DocumentSpaceRepository spaces;
    private final DocumentRepository documents;
    private final AttachmentRepository attachments;
    private final AttachmentDeletionIntents deletions;
    private final Clock clock;
    @Transactional(readOnly=true)
    public void authorize(UUID user,UUID document){access.read(user,documents.spaceOf(document).orElseThrow(DocumentException::missing));}
    @Transactional
    public AttachmentSnapshot insert(UUID user,UUID document,UUID id,String filename,String key,String type,long size,AtomicInteger completion) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){completion.set(status);}});
        var space=documents.spaceOf(document).orElseThrow(DocumentException::missing);
        var workspace=access.lock(user,space);
        spaces.find(space,true).orElseThrow(DocumentException::missing);
        documents.find(document,true).orElseThrow(DocumentException::missing);
        var attachment=Attachment.temporary(id,document,user,filename,key,type,size,clock.instant());
        attachments.insert(attachment);
        if(workspace.getDataRevision()==Long.MAX_VALUE)throw new DocumentException("REVISION_CONFLICT",null);
        workspace.recordBusinessMutation();attachments.flush();
        return AttachmentSnapshot.from(attachment,workspace.getDataRevision());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void compensateLater(UUID id,UUID document,String key){deletions.enqueue(id,document,key,"UPLOAD_ROLLBACK",clock.instant());}
}
