package com.kopite.devspace;
import com.kopite.devspace.attachment.application.*;
import com.kopite.devspace.attachment.infrastructure.LocalFileStorage;
import com.kopite.devspace.document.domain.DocumentException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class AttachmentLifecycleTests extends DocumentTestSupport {
    @Autowired AttachmentCleanupTransactions cleanup;@Autowired AttachmentCleanupWorker worker;@Autowired AttachmentCleanupStore jobs;@Autowired FileStorage storage;
    @Value("${app.storage.root}")String root;
    String document(Owner o,UUID journal)throws Exception{return id(tree(request(o,post(collection(journal)),"{\"title\":\"\",\"content\":[]}",201)));}
    String upload(Owner o,String doc)throws Exception{return id(tree(request(o,multipart("/api/v2/attachments").file(new MockMultipartFile("file","hello.txt","text/plain","hello".getBytes())).param("documentId",doc),null,201)));}
    String key(String id){return jdbc.queryForObject("select object_key from attachments where id=?",String.class,UUID.fromString(id));}
    String save(String id){return "{\"title\":\"\",\"content\":[],\"attachmentIds\":[\""+id+"\"]}";}
    void awaitCleanup(String key)throws Exception {long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(Files.exists(Path.of(root).resolve(key))&&System.nanoTime()<deadline)Thread.sleep(20);assertFalse(Files.exists(Path.of(root).resolve(key)));}
    @Test void streamsExactBytesAndSafeHeadersThenTempDeleteCompletes()throws Exception {
        var o=owner();String doc=document(o,journal(o)),id=upload(o,doc);String key=key(id);long before=counter(o);
        var started=mvc.perform(get("/api/v2/attachments/"+id+"/content").session(o.session())).andReturn();assertTrue(started.getRequest().isAsyncStarted());
        var response=mvc.perform(asyncDispatch(started)).andReturn().getResponse();assertEquals(200,response.getStatus());assertArrayEquals("hello".getBytes(),response.getContentAsByteArray());
        assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));assertEquals("5",response.getHeader("Content-Length"));assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
        request(owner(),get("/api/v2/attachments/"+id+"/content"),null,404);
        request(o,delete("/api/v2/attachments/"+id),null,200);assertEquals(before+1,counter(o));
        assertThrows(DocumentException.class,()->storage.open(key));request(o,get("/api/v2/attachments/"+id+"/content"),null,404);request(o,delete("/api/v2/attachments/"+id),null,404);
    }
    @Test void attachedDeleteRejectedAndCleanupClaimWinsAgainstSave()throws Exception {
        var o=owner();String doc=document(o,journal(o)),a=upload(o,doc);request(o,put(D+"/"+doc),save(a),200);
        request(o,delete("/api/v2/attachments/"+a),null,409);assertNull(cleanup.claim(null,UUID.fromString(a),Instant.now()));
        request(o,put(D+"/"+doc),"{\"title\":\"\",\"content\":[],\"attachmentIds\":[]}",200);
        var job=cleanup.claim(o.user(),UUID.fromString(a),null);assertNotNull(job);request(o,put(D+"/"+doc),save(a),409);request(o,get("/api/v2/attachments/"+a+"/content"),null,404);request(o,delete("/api/v2/attachments/"+a),null,409);
        worker.process(job);assertTrue(jobs.get(job).isEmpty());
    }
    @Test void cleanupStrictCutoffAndLeaseFencing()throws Exception {
        var o=owner();String doc=document(o,journal(o)),a=upload(o,doc);var cutoff=Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update("update attachments set updated_at=? where id=?",Timestamp.from(cutoff),UUID.fromString(a));assertNull(cleanup.claim(null,UUID.fromString(a),cutoff));
        jdbc.update("update attachments set updated_at=? where id=?",Timestamp.from(cutoff.minusNanos(1000)),UUID.fromString(a));
        var id=cleanup.claim(null,UUID.fromString(a),cutoff);var first=cleanup.lease(id).orElseThrow();assertTrue(cleanup.lease(id).isEmpty());
        jdbc.update("update attachment_object_deletions set lease_until='2020-01-01' where id=?",id);var second=cleanup.lease(id).orElseThrow();
        assertThrows(DocumentException.class,()->cleanup.finish(first));storage.delete(second.key());cleanup.finish(second);assertTrue(jobs.get(id).isEmpty());
    }
    @Test void failedFileDeleteKeepsClaimAndSurvivesNewWorkerInstance()throws Exception {
        var o=owner();String doc=document(o,journal(o)),a=upload(o,doc);String key=key(a);long before=counter(o);var marker=Path.of(root,LocalFileStorage.MARKER);
        Files.delete(marker);try{request(o,delete("/api/v2/attachments/"+a),null,503);}finally{Files.writeString(marker,LocalFileStorage.MARKER_VALUE);}
        assertEquals(before,counter(o));var job=jobs.jobFor(UUID.fromString(a)).orElseThrow();assertEquals(1,jdbc.queryForObject("select attempts from attachment_object_deletions where id=?",Integer.class,job));
        jdbc.update("update attachment_object_deletions set next_attempt_at='2020-01-01' where id=?",job);
        new AttachmentCleanupWorker(cleanup,new LocalFileStorage(root)).process(job);assertTrue(jobs.get(job).isEmpty());assertEquals(before+1,counter(o));assertThrows(DocumentException.class,()->storage.open(key));
    }
    @Test void documentDeleteRetainsSpaceAndJournalDeleteRemovesChildrenAndReusesClaims()throws Exception {
        var o=owner();var j=journal(o);String doc=document(o,j),a=upload(o,doc);String key=key(a);var space=jdbc.queryForObject("select document_space_id from journals where id=?",UUID.class,j);
        var claim=cleanup.claim(o.user(),UUID.fromString(a),null);long before=counter(o);request(o,delete(D+"/"+doc),null,200);assertEquals(before+1,counter(o));
        assertEquals(1,jdbc.queryForObject("select count(*) from document_spaces where id=?",Integer.class,space));awaitCleanup(key);assertThrows(DocumentException.class,()->storage.open(key));
        String second=document(o,j),b=upload(o,second);String keyB=key(b);request(o,put(D+"/"+second),save(b),200);
        before=counter(o);long revision=jdbc.queryForObject("select revision from journals where id=?",Long.class,j);request(o,delete("/api/v2/journals/"+j).param("revision",Long.toString(revision)),null,200);
        assertEquals(before+1,counter(o));assertEquals(0,jdbc.queryForObject("select count(*) from document_spaces where id=?",Integer.class,space));awaitCleanup(keyB);assertThrows(DocumentException.class,()->storage.open(keyB));
    }
    @Test void parentRollbackPreservesMetadataAndFilesAndNoDeletionIntent()throws Exception {
        var o=owner();String doc=document(o,journal(o)),a=upload(o,doc),key=key(a);
        jdbc.update("update workspaces set data_revision=? where id=?",Long.MAX_VALUE,o.workspace());request(o,delete(D+"/"+doc),null,409);
        request(o,get(D+"/"+doc),null,200);assertTrue(jobs.jobFor(UUID.fromString(a)).isEmpty());try(var input=storage.open(key)){assertEquals('h',input.read());}
    }
    @Test void fileGoneButDatabaseFinalizeFailureRetainsDurableJob()throws Exception {
        var o=owner();String doc=document(o,journal(o)),a=upload(o,doc),key=key(a);long revision=counter(o);
        var job=cleanup.claim(o.user(),UUID.fromString(a),null);
        jdbc.update("update workspaces set data_revision=? where id=?",Long.MAX_VALUE,o.workspace());
        assertThrows(DocumentException.class,()->worker.process(job));assertTrue(jobs.get(job).isPresent());assertThrows(DocumentException.class,()->storage.open(key));
        assertEquals(1,jdbc.queryForObject("select count(*) from attachments where id=?",Integer.class,UUID.fromString(a)));
        jdbc.update("update workspaces set data_revision=? where id=?",revision,o.workspace());jdbc.update("update attachment_object_deletions set next_attempt_at='2020-01-01' where id=?",job);
        worker.process(job);assertTrue(jobs.get(job).isEmpty());assertEquals(revision+1,counter(o));
    }
}
