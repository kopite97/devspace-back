package com.kopite.devspace.attachment.application;
import lombok.RequiredArgsConstructor;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Clock;
@Service
public class AttachmentCleanupService {
    private static final Logger log=LoggerFactory.getLogger(AttachmentCleanupService.class);
    private final AttachmentCleanupStore store;private final AttachmentCleanupTransactions transactions;private final AttachmentCleanupWorker worker;private final Clock clock;
    private final long graceSeconds;private final int batch;
    public AttachmentCleanupService(AttachmentCleanupStore store,AttachmentCleanupTransactions transactions,AttachmentCleanupWorker worker,Clock clock,@Value("${app.attachment.cleanup.grace-seconds:86400}")long grace,@Value("${app.attachment.cleanup.batch-size:100}")int batch) {
        if(grace<1||batch<1||batch>1000)throw new IllegalArgumentException("Invalid attachment cleanup bounds");
        this.store=store;this.transactions=transactions;this.worker=worker;this.clock=clock;this.graceSeconds=grace;this.batch=batch;
    }
    public void runOnce() {
        var cutoff=clock.instant().minusSeconds(graceSeconds);
        for(var id:store.expired(cutoff,batch))try{transactions.claim(null,id,cutoff);}catch(RuntimeException race){log.debug("TEMP cleanup candidate deferred; attachmentId={}",id);}
        drain(batch);
    }
    public void drain(int limit){for(var id:store.due(clock.instant(),Math.min(limit,batch)))try{worker.process(id);}catch(RuntimeException failure){log.debug("Attachment cleanup retained for retry; jobId={}",id);}}
}
