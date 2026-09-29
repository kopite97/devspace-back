package com.kopite.devspace;
import com.kopite.devspace.attachment.application.*;
import com.kopite.devspace.document.application.DocumentCommandService;
import com.kopite.devspace.document.domain.DocumentException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class AttachmentConcurrencyTests extends DocumentTestSupport {
    @Autowired DocumentCommandService commands;@Autowired AttachmentCleanupTransactions cleanup;@Autowired FileStorage storage;@Autowired PlatformTransactionManager manager;
    @Autowired com.kopite.devspace.document.application.DocumentDeletionService deletions;
    UUID document(Owner o)throws Exception{return UUID.fromString(id(tree(request(o,post(collection(journal(o))),"{\"title\":\"\",\"content\":[]}",201))));}
    UUID upload(Owner o,UUID doc)throws Exception{return UUID.fromString(id(tree(request(o,multipart("/api/v2/attachments").file(new MockMultipartFile("file","a.txt","text/plain",new byte[]{65})).param("documentId",doc.toString()),null,201))));}
    static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    @Test void saveHoldingWorkspaceLockWinsAndCleanupRechecksState()throws Exception {
        for(int iteration=0;iteration<3;iteration++) {
            var o=owner();var doc=document(o);var id=upload(o,doc);var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
            try(var executor=Executors.newFixedThreadPool(2)) {
                var save=executor.submit(()->new TransactionTemplate(manager).execute(status->{var result=commands.update(o.user(),doc,"saved","[]",List.of(id));ready.countDown();await(release);return result;}));
                await(ready);var deletion=executor.submit(()->cleanup.claim(null,id,Instant.now().plusSeconds(1)));
                try{assertThrows(TimeoutException.class,()->deletion.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}
                assertEquals(List.of(id),save.get(10,TimeUnit.SECONDS).attachmentIds());assertNull(deletion.get(10,TimeUnit.SECONDS));
                var key=jdbc.queryForObject("select object_key from attachments where id=?",String.class,id);try(var input=storage.open(key)){assertEquals(65,input.read());}
            }finally{release.countDown();}
        }
    }
    @Test void committedClaimWinsAndEveryConcurrentSaveRollsBack()throws Exception {
        var o=owner();var doc=document(o);var id=upload(o,doc);cleanup.claim(o.user(),id,null);long before=counter(o);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(3)) {
            var saves=new ArrayList<Future<String>>();for(int i=0;i<3;i++)saves.add(executor.submit(()->{await(start);try{commands.update(o.user(),doc,"must not save","[]",List.of(id));return "unexpected";}catch(DocumentException conflict){return conflict.code();}}));
            start.countDown();for(var save:saves)assertEquals("ATTACHMENT_DELETION_PENDING",save.get(10,TimeUnit.SECONDS));
        }
        assertEquals(before,counter(o));assertEquals("",jdbc.queryForObject("select title from documents where id=?",String.class,doc));
    }
    @Test void concurrentDocumentDeletionRechecksExistenceAfterWorkspaceLock()throws Exception {
        var o=owner();var doc=document(o);long before=counter(o);var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var results=new ArrayList<Future<String>>();for(int i=0;i<4;i++)results.add(executor.submit(()->{await(start);try{deletions.delete(o.user(),doc);return "deleted";}catch(DocumentException missing){return missing.code();}}));
            start.countDown();int deleted=0;for(var result:results){String value=result.get(10,TimeUnit.SECONDS);if(value.equals("deleted"))deleted++;else assertEquals("RESOURCE_NOT_FOUND",value);}assertEquals(1,deleted);
        }
        assertEquals(before+1,counter(o));
    }
}
