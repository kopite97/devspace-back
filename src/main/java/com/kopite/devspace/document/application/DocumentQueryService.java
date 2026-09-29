package com.kopite.devspace.document.application;
import com.kopite.devspace.document.domain.*;
import com.kopite.devspace.documentspace.application.DocumentSpaceAccess;
import com.kopite.devspace.attachment.domain.AttachmentRepository;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class DocumentQueryService {
    private final DocumentSpaceAccess access;private final DocumentRepository documents;
    private final DocumentSnapshotReader snapshots;private final DocumentCursorCodec cursors;
    @io.swagger.v3.oas.annotations.media.Schema(name="DocumentPage")
    public record Page(List<DocumentRepository.DocumentSummary> items,long total,String nextCursor,@JsonIgnore Long dataRevision){}
    public DocumentSnapshot get(UUID user,UUID id) {
        UUID space=documents.spaceOf(id).orElseThrow(DocumentException::missing);
        var workspace=access.read(user,space);
        var document=documents.find(id,false).orElseThrow(DocumentException::missing);
        return snapshots.read(document,workspace.getDataRevision());
    }
    public Page list(UUID user,UUID space,String collection,int limit,String cursor) {
        if(limit<1||limit>100)throw DocumentException.invalid("limit");
        var workspace=access.read(user,space);String context=space+":"+collection+":"+limit;
        Instant at=null;UUID id=null;
        if(cursor!=null)try{var parts=cursors.decode(workspace.getId(),context,cursor).split("/",-1);if(parts.length!=2)throw new IllegalArgumentException();at=Instant.parse(parts[0]);id=UUID.fromString(parts[1]);}
            catch(IllegalArgumentException|java.time.DateTimeException e){throw new DocumentException("INVALID_CURSOR","cursor");}
        var rows=documents.page(space,limit,at,id);var page=rows.stream().limit(limit).toList();String next=null;
        if(rows.size()>limit){var last=page.getLast();next=cursors.encode(workspace.getId(),context,last.createdAt()+"/"+last.id());}
        return new Page(page,documents.count(space),next,workspace.getDataRevision());
    }
}
