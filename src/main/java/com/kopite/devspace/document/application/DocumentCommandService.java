package com.kopite.devspace.document.application;
import com.kopite.devspace.document.domain.*;
import com.kopite.devspace.documentspace.application.DocumentSpaceAccess;
import com.kopite.devspace.documentspace.domain.DocumentSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;
@Service @RequiredArgsConstructor
public class DocumentCommandService {
    private final DocumentSpaceAccess access;
    private final DocumentSpaceRepository spaces;
    private final DocumentRepository documents;
    private final Clock clock;
    private final DocumentLimits limits;
    private final DocumentSnapshotReader snapshots;
    private final com.kopite.devspace.attachment.domain.AttachmentRepository attachments;
    private final com.kopite.devspace.attachment.application.AttachmentDeletionIntents deletions;
    @Transactional public DocumentSnapshot create(UUID user,UUID space,String title,String content) {
        var workspace=access.lock(user,space);
        var parent=spaces.find(space,true).orElseThrow(DocumentException::missing);
        limits.validateContent(content);
        var now=clock.instant();var document=Document.draft(space,user,title,new DocumentContent(content),now);
        documents.insert(document);parent.collectionChanged(now);mutate(workspace);
        return DocumentSnapshot.from(document,List.of(),workspace.getDataRevision());
    }
    @Transactional public DocumentSnapshot update(UUID user,UUID id,String title,String content,List<UUID> ids) {
        var space=documents.spaceOf(id).orElseThrow(DocumentException::missing);var workspace=access.lock(user,space);
        var parent=spaces.find(space,true).orElseThrow(DocumentException::missing);var document=documents.find(id,true).orElseThrow(DocumentException::missing);
        limits.validateContent(content);
        if(ids==null||ids.size()>100||ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())throw DocumentException.invalid("attachmentIds");
        var desired=new HashSet<>(ids);var all=new TreeSet<UUID>();all.addAll(attachments.attachedIds(id));all.addAll(ids);
        var locked=new ArrayList<com.kopite.devspace.attachment.domain.Attachment>();
        for(var attachmentId:all) {
            var attachment=attachments.find(attachmentId,true).orElseThrow(DocumentException::missing);
            if(!attachment.getDocumentId().equals(id))throw DocumentException.missing();
            locked.add(attachment);
        }
        if(deletions.anyPending(all))throw new DocumentException("ATTACHMENT_DELETION_PENDING","attachmentIds");
        var now=clock.instant();document.save(title,new DocumentContent(content),now);
        for(var attachment:locked){if(desired.contains(attachment.getId()))attachment.attach(now);else attachment.detach(now);}
        parent.collectionChanged(now);mutate(workspace);attachments.flush();
        return snapshots.read(document,workspace.getDataRevision());
    }
    @Transactional public DocumentSnapshot publish(UUID user,UUID id) {
        var space=documents.spaceOf(id).orElseThrow(DocumentException::missing);var workspace=access.lock(user,space);
        var parent=spaces.find(space,true).orElseThrow(DocumentException::missing);var document=documents.find(id,true).orElseThrow(DocumentException::missing);
        var now=clock.instant();if(document.publish(now)){parent.collectionChanged(now);mutate(workspace);}
        return snapshots.read(document,workspace.getDataRevision());
    }
    private void mutate(com.kopite.devspace.workspace.domain.PersonalWorkspace workspace) {
        if(workspace.getDataRevision()==Long.MAX_VALUE)throw new DocumentException("REVISION_CONFLICT",null);
        workspace.recordBusinessMutation();
    }
}
