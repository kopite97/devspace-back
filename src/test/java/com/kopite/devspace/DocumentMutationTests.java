package com.kopite.devspace;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class DocumentMutationTests extends DocumentTestSupport {
    String document(Owner o)throws Exception{return id(tree(request(o,post(collection(journal(o))),"{\"title\":\"\",\"content\":[]}",201)));}
    String upload(Owner o,String doc)throws Exception{return id(tree(request(o,multipart("/api/v2/attachments").file(new MockMultipartFile("file","a.txt","text/plain",new byte[]{65})).param("documentId",doc),null,201)));}
    String save(String... ids){return "{\"title\":\"saved\",\"content\":[{\"unknown\":{\"id\":\"ignored\"}}],\"attachmentIds\":"+json.writeValueAsString(ids)+"}";}
    Map<String,Object> attachment(String id){return jdbc.queryForMap("select status,created_at,updated_at from attachments where id=?",UUID.fromString(id));}
    @Test void reconcilesBothDirectionsWithoutInterpretingBlocksAndPreservesUnchangedAudit()throws Exception {
        var o=owner();String doc=document(o),a=upload(o,doc),b=upload(o,doc);long before=counter(o);
        var untouched=attachment(b);var first=tree(request(o,put(D+"/"+doc),save(a),200));assertEquals(a,first.path("attachmentIds").get(0).asString());assertEquals("ATTACHED",attachment(a).get("status"));assertEquals(untouched,attachment(b));
        var attached=attachment(a);request(o,put(D+"/"+doc),save(a),200);assertEquals(attached,attachment(a));
        jdbc.update("update attachments set created_at='2020-01-01',updated_at='2020-01-01' where id=?",UUID.fromString(a));
        request(o,put(D+"/"+doc),save(),200);assertEquals("TEMP",attachment(a).get("status"));assertNotEquals(attachment(a).get("created_at"),attachment(a).get("updated_at"));
        request(o,put(D+"/"+doc),save(a,b),200);assertEquals(2,tree(request(o,get(D+"/"+doc),null,200)).path("attachmentIds").size());assertEquals(before+4,counter(o));
    }
    @Test void invalidRelationshipOrClaimRollsBackEverything()throws Exception {
        var o=owner();String doc=document(o),a=upload(o,doc),other=upload(o,document(o));var baseline=tree(request(o,get(D+"/"+doc),null,200));long before=counter(o);var original=attachment(a);
        for(String body:List.of(save(a,UUID.randomUUID().toString()),save(a,other)))request(o,put(D+"/"+doc),body,404);
        for(String body:List.of(save(a,a),"{\"title\":\"bad\",\"content\":[]}","{\"title\":\"bad\",\"content\":[],\"attachmentIds\":null}"))request(o,put(D+"/"+doc),body,400);
        jdbc.update("insert into attachment_object_deletions(id,attachment_id,document_id,object_key,reason,next_attempt_at,created_at) select ?,id,document_id,object_key,'TEST',now(),now() from attachments where id=?",UUID.randomUUID(),UUID.fromString(a));
        request(o,put(D+"/"+doc),save(a),409);
        assertEquals(baseline,tree(request(o,get(D+"/"+doc),null,200)));assertEquals(before,counter(o));assertEquals(original,attachment(a));
    }
    @Test void publishIsIdempotentAndPublishedDocumentRemainsEditable()throws Exception {
        var o=owner();String doc=document(o);long before=counter(o);
        var published=tree(request(o,post(D+"/"+doc+"/publish"),null,200));assertEquals("PUBLISHED",published.path("status").asString());assertFalse(published.path("publishedAt").isNull());assertEquals(before+1,counter(o));
        assertEquals(published,tree(request(o,post(D+"/"+doc+"/publish"),null,200)));assertEquals(before+1,counter(o));
        var saved=tree(request(o,put(D+"/"+doc),save(),200));assertEquals(published.path("publishedAt"),saved.path("publishedAt"));assertEquals("PUBLISHED",saved.path("status").asString());assertEquals(before+2,counter(o));
    }
    @Test void counterOverflowRollsBackAfterRelationshipChanges()throws Exception {
        var o=owner();String doc=document(o),a=upload(o,doc);var before=tree(request(o,get(D+"/"+doc),null,200));var original=attachment(a);
        jdbc.update("update workspaces set data_revision=? where id=?",Long.MAX_VALUE,o.workspace());
        request(o,put(D+"/"+doc),save(a),409);assertEquals(before,tree(request(o,get(D+"/"+doc),null,200)));assertEquals(original,attachment(a));
    }
}
