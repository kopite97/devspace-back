package com.kopite.devspace.attachment.application;
import com.kopite.devspace.document.domain.DocumentException;
import lombok.RequiredArgsConstructor;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
@Service @RequiredArgsConstructor
public class AttachmentUploadService {
    private static final Logger log=LoggerFactory.getLogger(AttachmentUploadService.class);
    private final FileStorage storage;
    private final UploadMetadataService metadata;
    private final AttachmentLimits limits;
    public AttachmentSnapshot upload(UUID user,UUID document,String filename,String type,InputStream input) {
        metadata.authorize(user,document);
        var validated=UploadValidation.validate(filename,type,input);
        UUID id=UUID.randomUUID();String key="documents/"+document+"/"+id+"."+validated.extension();
        long size=storage.put(key,validated.input(),limits.maxBytes());
        var completion=new AtomicInteger(-1);
        try{return metadata.insert(user,document,id,filename,key,validated.contentType(),size,completion);}
        catch(RuntimeException failure){
            if(completion.get()==TransactionSynchronization.STATUS_ROLLED_BACK) {
                try{storage.delete(key);}catch(RuntimeException unavailable){
                    try{metadata.compensateLater(id,document,key);}catch(RuntimeException databaseUnavailable){log.error("Attachment rollback needs operator reconciliation; attachmentId={}",id);}
                }
            } else {
                // A failed commit acknowledgement is not proof of rollback. Never delete a possibly committed file.
                log.error("Attachment upload outcome needs reconciliation after original transaction termination; attachmentId={}",id);
            }
            if(failure instanceof DocumentException known)throw known;
            throw new DocumentException("STORAGE_UNAVAILABLE",null);
        }
    }
}
