package com.kopite.devspace.document.application;

import com.kopite.devspace.attachment.application.AttachedSnapshot;
import com.kopite.devspace.attachment.application.AttachmentDeletionIntents;
import com.kopite.devspace.attachment.domain.AttachmentRepository;
import com.kopite.devspace.document.domain.Document;
import com.kopite.devspace.document.domain.DocumentIntegrityException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Called inside the authorized query/command transaction; performs no storage I/O. */
@Component @RequiredArgsConstructor
public class DocumentSnapshotReader {
    private final AttachmentRepository attachments;
    private final AttachmentDeletionIntents deletions;

    public DocumentSnapshot read(Document document,long revision) {
        var rows=attachments.attached(document.getId());
        if(rows.size()>100)throw new DocumentIntegrityException(document.getId(),"Attachment limit violated");
        var snapshots=rows.stream().map(AttachedSnapshot::from).toList();
        if(deletions.anyPending(snapshots.stream().map(AttachedSnapshot::id).toList()))
            throw new DocumentIntegrityException(document.getId(),"Saved attachment has deletion claim");
        return DocumentSnapshot.from(document,snapshots,revision);
    }
}
