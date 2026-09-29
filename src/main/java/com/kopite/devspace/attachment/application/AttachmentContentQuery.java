package com.kopite.devspace.attachment.application;
import com.kopite.devspace.attachment.domain.AttachmentRepository;
import com.kopite.devspace.document.domain.*;
import com.kopite.devspace.documentspace.application.DocumentSpaceAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class AttachmentContentQuery {
    private final AttachmentRepository attachments;private final DocumentRepository documents;private final DocumentSpaceAccess access;private final AttachmentDeletionIntents intents;
    public record Content(String key,String filename,String type,long size,long revision){}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Content get(UUID user,UUID id) {
        var document=attachments.documentOf(id).orElseThrow(DocumentException::missing);
        var workspace=access.read(user,documents.spaceOf(document).orElseThrow(DocumentException::missing));
        if(intents.pending(id))throw DocumentException.missing();
        var a=attachments.find(id,false).orElseThrow(DocumentException::missing);
        return new Content(a.getObjectKey(),a.getOriginalFilename(),a.getContentType(),a.getSize(),workspace.getDataRevision());
    }
}
