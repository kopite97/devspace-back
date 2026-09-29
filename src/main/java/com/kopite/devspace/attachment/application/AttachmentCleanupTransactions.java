package com.kopite.devspace.attachment.application;
import com.kopite.devspace.attachment.domain.*;
import com.kopite.devspace.document.domain.*;
import com.kopite.devspace.documentspace.application.DocumentSpaceAccess;
import com.kopite.devspace.documentspace.domain.DocumentSpaceRepository;
import com.kopite.devspace.workspace.domain.PersonalWorkspace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
@Service @RequiredArgsConstructor
public class AttachmentCleanupTransactions {
    private final AttachmentCleanupStore store;
    private final AttachmentDeletionIntents intents;
    private final AttachmentRepository attachments;
    private final DocumentRepository documents;
    private final DocumentSpaceRepository spaces;
    private final DocumentSpaceAccess access;
    private final Clock clock;
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public UUID claim(UUID user,UUID attachment,Instant cutoff) {
        var document=attachments.documentOf(attachment).orElseThrow(DocumentException::missing);
        var space=documents.spaceOf(document).orElseThrow(DocumentException::missing);
        var workspace=user==null?access.maintenance(space):access.lock(user,space);
        if(workspace.getDataRevision()==Long.MAX_VALUE)throw new DocumentException("REVISION_CONFLICT",null);
        spaces.find(space,true).orElseThrow(DocumentException::missing);documents.find(document,true).orElseThrow(DocumentException::missing);
        var a=attachments.find(attachment,true).orElseThrow(DocumentException::missing);
        if(a.getStatus()!=Attachment.Status.TEMP){if(user==null)return null;throw new DocumentException("ATTACHMENT_IN_USE",null);}
        if(cutoff!=null&&!a.getUpdatedAt().isBefore(cutoff))return null;
        if(intents.pending(attachment)){if(user==null)return null;throw new DocumentException("ATTACHMENT_DELETION_PENDING",null);}
        intents.enqueue(attachment,document,a.getObjectKey(),user==null?"TEMP_EXPIRED":"DIRECT_DELETE",clock.instant());
        return store.jobFor(attachment).orElseThrow();
    }
    private PersonalWorkspace lockLive(AttachmentCleanupStore.Job job) {
        if(job.documentId()==null)return null;
        var space=documents.spaceOf(job.documentId());if(space.isEmpty())return null;
        var workspace=access.maintenance(space.get());
        spaces.find(space.get(),true).orElseThrow(DocumentException::missing);documents.find(job.documentId(),true).orElseThrow(DocumentException::missing);
        if(job.attachmentId()!=null)attachments.find(job.attachmentId(),true);
        return workspace;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<AttachmentCleanupStore.Job> lease(UUID id) {
        var job=store.get(id);if(job.isEmpty())return Optional.empty();lockLive(job.get());
        return store.lease(id,clock.instant(),clock.instant().plusSeconds(300));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Long finish(AttachmentCleanupStore.Job job) {
        var workspace=lockLive(job);
        if(!store.finish(job))throw new DocumentException("ATTACHMENT_DELETION_PENDING",null);
        var attachment=job.attachmentId()==null?Optional.<Attachment>empty():attachments.find(job.attachmentId(),true);
        if(attachment.isPresent()) {
            if(attachment.get().getStatus()!=Attachment.Status.TEMP)throw new IllegalStateException("Deletion claim cannot target a live ATTACHED object");
            attachments.delete(attachment.get());
            if(workspace==null)throw new IllegalStateException("Attachment parent missing");
            if(workspace.getDataRevision()==Long.MAX_VALUE)throw new DocumentException("REVISION_CONFLICT",null);
            workspace.recordBusinessMutation();
        }
        return workspace==null?null:workspace.getDataRevision();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void retry(AttachmentCleanupStore.Job job){store.retry(job,clock.instant().plusSeconds(600));}
}
