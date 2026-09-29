package com.kopite.devspace.attachment.application;
import com.kopite.devspace.document.domain.DocumentException;
import lombok.RequiredArgsConstructor;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class AttachmentCleanupWorker {
    private static final Logger log=LoggerFactory.getLogger(AttachmentCleanupWorker.class);
    private final AttachmentCleanupTransactions transactions;
    private final FileStorage storage;
    public Long process(UUID id) {
        var job=transactions.lease(id).orElseThrow(()->new DocumentException("ATTACHMENT_DELETION_PENDING",null));
        try{storage.delete(job.key());return transactions.finish(job);}
        catch(RuntimeException failure){
            try{transactions.retry(job);}catch(RuntimeException databaseFailure){log.error("Attachment deletion retry will resume after lease expiry; jobId={}",id);}
            log.warn("Attachment deletion delayed; jobId={}",id);
            throw new DocumentException("STORAGE_UNAVAILABLE",null);
        }
    }
}
