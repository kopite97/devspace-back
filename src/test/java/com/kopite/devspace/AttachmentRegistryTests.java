package com.kopite.devspace;

import com.kopite.devspace.attachment.application.*;
import com.kopite.devspace.attachment.domain.*;
import com.kopite.devspace.document.application.*;
import com.kopite.devspace.attachment.infrastructure.LocalFileStorage;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AttachmentRegistryTests extends DocumentTestSupport {
    @Autowired AttachmentRepository repository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DocumentQueryService queries;
    @Autowired DocumentCommandService commands;
    @Autowired DocumentDeletionService deletion;
    @Autowired AttachmentCleanupTransactions cleanup;
    @Autowired AttachmentCleanupWorker worker;
    @Autowired EntityManagerFactory emf;
    @Value("${app.storage.root}") String root;

    String document(Owner o)throws Exception{return id(tree(request(o,post(collection(journal(o))),"{\"title\":\"\",\"content\":[]}",201)));}
    String save(List<String> ids)throws Exception{return json.writeValueAsString(Map.of("title","saved","content",List.of(),"attachmentIds",ids));}
    String upload(Owner o,String doc)throws Exception{return id(tree(request(o,multipart("/api/v2/attachments").file(new MockMultipartFile("file","hello.txt","text/plain","hello".getBytes())).param("documentId",doc),null,201)));}
    List<String> seed(Owner o,String doc,int count,boolean attached) {
        return new TransactionTemplate(transactions).execute(tx->{var ids=new ArrayList<String>();for(int i=0;i<count;i++) {
            var id=UUID.randomUUID();var a=Attachment.temporary(id,UUID.fromString(doc),o.user(),"item-"+i+".txt","documents/"+doc+"/"+id+".txt","text/plain",i+1,Instant.parse("2026-09-23T00:00:00Z"));
            if(attached)a.attach(Instant.now());repository.insert(a);ids.add(id.toString());
        }repository.flush();return ids;});
    }
    @Test void restoresAllHundredMetadataWithExtraTempsAndConstantQueryCount()throws Exception {
        var o=owner();String doc=document(o);var ids=seed(o,doc,100,true);seed(o,doc,3,false);
        String pending=upload(o,doc);cleanup.claim(o.user(),UUID.fromString(pending),null);
        var stats=emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();stats.setStatisticsEnabled(true);
        try {
            String single=document(o);seed(o,single,1,true);stats.clear();queries.get(o.user(),UUID.fromString(single));long one=stats.getPrepareStatementCount();
            long before=counter(o);stats.clear();var restored=queries.get(o.user(),UUID.fromString(doc));long hundred=stats.getPrepareStatementCount();assertEquals(one,hundred);
            assertEquals(100,restored.attachments().size());assertEquals(new HashSet<>(ids),new HashSet<>(restored.attachmentIds().stream().map(UUID::toString).toList()));
            assertEquals(restored.attachmentIds(),restored.attachments().stream().map(AttachedSnapshot::id).toList());
            assertEquals(ids.stream().sorted().toList(),restored.attachmentIds().stream().map(UUID::toString).toList());
            var response=tree(request(o,get(D+"/"+doc),null,200));
            for(var a:response.path("attachments")){assertEquals(8,a.size());assertEquals(doc,a.path("documentId").asString());assertEquals("ATTACHED",a.path("status").asString());assertEquals("text/plain",a.path("contentType").asString());assertTrue(a.path("originalFilename").asString().startsWith("item-"));assertTrue(a.path("size").asLong()>0);assertEquals("2026-09-23T00:00:00Z",a.path("createdAt").asString());assertEquals("/api/v2/attachments/"+a.path("id").asString()+"/content",a.path("contentUrl").asString());}
            assertEquals(before,counter(o));request(owner(),get(D+"/"+doc),null,404);
        }finally{stats.setStatisticsEnabled(false);}
    }
    @Test void mutationSnapshotsAndMissingBinaryPreserveMetadata()throws Exception {
        var o=owner();String doc=document(o),a=upload(o,doc);var saved=tree(request(o,put(D+"/"+doc),save(List.of(a)),200));
        assertEquals(saved.path("attachments"),tree(request(o,post(D+"/"+doc+"/publish"),null,200)).path("attachments"));
        assertEquals(saved.path("attachments"),tree(request(o,post(D+"/"+doc+"/publish"),null,200)).path("attachments"));
        String key=jdbc.queryForObject("select object_key from attachments where id=?",String.class,UUID.fromString(a));Files.delete(Path.of(root).resolve(key));
        assertEquals(saved.path("attachments"),tree(request(o,get(D+"/"+doc),null,200)).path("attachments"));request(o,get("/api/v2/attachments/"+a+"/content"),null,404);
        var marker=Path.of(root,LocalFileStorage.MARKER);Files.delete(marker);
        try{assertEquals(saved.path("attachments"),tree(request(o,get(D+"/"+doc),null,200)).path("attachments"));request(o,get("/api/v2/attachments/"+a+"/content"),null,503);}finally{Files.writeString(marker,LocalFileStorage.MARKER_VALUE);}
        assertEquals(0,tree(request(o,put(D+"/"+doc),save(List.of()),200)).path("attachments").size());
        assertEquals(a,tree(request(o,delete("/api/v2/attachments/"+a),null,200)).path("deletedId").asString());
        assertEquals(doc,tree(request(o,delete(D+"/"+doc),null,200)).path("deletedId").asString());
    }
    @Test void invalidMetadataNeverReturnsPartialSuccess()throws Exception {
        var o=owner();String doc=document(o);seed(o,doc,101,true);
        assertEquals("INTERNAL_ERROR",tree(request(o,get(D+"/"+doc),null,500)).path("code").asString());
        String second=document(o),a=upload(o,second);cleanup.claim(o.user(),UUID.fromString(a),null);jdbc.update("update attachments set status='ATTACHED' where id=?",UUID.fromString(a));
        assertEquals("INTERNAL_ERROR",tree(request(o,get(D+"/"+second),null,500)).path("code").asString());
    }
    @Test void membershipErrorsWinOverClaimsRegardlessOfOrder()throws Exception {
        var o=owner();String doc=document(o),pending=upload(o,doc),other=upload(o,document(o));var foreign=owner();String foreignId=upload(foreign,document(foreign));cleanup.claim(o.user(),UUID.fromString(pending),null);
        var baseline=tree(request(o,get(D+"/"+doc),null,200));long before=counter(o);
        for(String invalid:List.of("00000000-0000-0000-0000-000000000000","ffffffff-ffff-ffff-ffff-ffffffffffff",other,foreignId))for(var ids:List.of(List.of(pending,invalid),List.of(invalid,pending)))
            assertEquals("RESOURCE_NOT_FOUND",tree(request(o,put(D+"/"+doc),save(ids),404)).path("code").asString());
        assertEquals("ATTACHMENT_DELETION_PENDING",tree(request(o,put(D+"/"+doc),save(List.of(pending)),409)).path("code").asString());
        assertEquals(before,counter(o));assertEquals(baseline,tree(request(o,get(D+"/"+doc),null,200)));
        request(o,put(D+"/"+doc),"{\"title\":\"\",\"content\":[],\"attachmentIds\":[],\"attachments\":[]}",400);
        request(o,post(collection(journal(o))),"{\"title\":\"\",\"content\":[],\"attachments\":[]}",400);
        request(o,put(D+"/"+doc),save(java.util.stream.IntStream.range(0,101).mapToObj(i->UUID.randomUUID().toString()).toList()),400);
        request(o,put(D+"/"+doc),save(List.of("not-a-uuid")),400);
        assertEquals(401,mvc.perform(get(D+"/"+doc)).andReturn().getResponse().getStatus());
        UUID project=jdbc.queryForObject("select project_id from journals where document_space_id=(select document_space_id from documents where id=?)",UUID.class,UUID.fromString(doc));
        jdbc.update("update projects set status='archived' where id=?",project);
        assertEquals(baseline,tree(request(o,get(D+"/"+doc),null,200)));
        jdbc.update("update users set disabled_at=now() where id=?",o.user());
        request(o,get(D+"/"+doc),null,403);
    }
    @Test void repeatableReadRestoresCompleteSnapshotDuringDetachAndParentDelete()throws Exception {
        for(boolean deleteParent:List.of(false,true)) {
            var o=owner();String doc=document(o);seed(o,doc,2,true);var id=UUID.fromString(doc);
            var read=new TransactionTemplate(transactions);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            try(var executor=Executors.newSingleThreadExecutor()) {
                read.executeWithoutResult(tx->{var before=queries.get(o.user(),id);
                    try{executor.submit(()->{if(deleteParent)deletion.delete(o.user(),id);else commands.update(o.user(),id,"changed","[]",List.of());}).get(10,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
                    assertEquals(before,queries.get(o.user(),id));assertEquals(2,before.attachments().size());
                });
            }
            if(deleteParent)request(o,get(D+"/"+doc),null,404);else assertEquals(0,tree(request(o,get(D+"/"+doc),null,200)).path("attachments").size());
        }
    }
    @Test void documentByteLimitsMeasureContentAndRequestSeparately()throws Exception {
        var o=owner();var path=collection(journal(o));String value="\uac00".repeat(349524); // 1048572 UTF-8 bytes + [""] = 1048576
        String body="{\"title\":\"\",\"content\":[\""+value+"\"]}";request(o,post(path),body,201);
        request(o,post(path),"{\"title\":\"\",\"content\":[\""+value+"x\"]}",413);
        String small="{\"title\":\"\",\"content\":[]}";String exact=small+" ".repeat(1114112-small.length());request(o,post(path),exact,201);request(o,post(path),exact+" ",413);
    }
}
