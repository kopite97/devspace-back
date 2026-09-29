package com.kopite.devspace;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class DocumentApiTests extends DocumentTestSupport {
    @Test void draftCreateDetailAndEmptyJournalMaintainContract()throws Exception {
        var o=owner();UUID j=journal(o);String c=collection(j);
        var empty=tree(request(o,get(c),null,200));assertEquals(0,empty.path("total").asLong());assertTrue(empty.path("nextCursor").isNull());
        long before=counter(o);String body="{\"title\":\"\",\"content\":[{\"type\":\"unknown-block\",\"props\":{\"future\":[null,true,12345678901234567890,1.23]},\"children\":[]}]}";
        var created=tree(request(o,post(c),body,201));assertEquals("DRAFT",created.path("status").asString());assertTrue(created.path("publishedAt").isNull());assertTrue(created.path("content").isArray());assertEquals(0,created.path("attachmentIds").size());
        var detail=tree(request(o,get(D+"/"+id(created)),null,200));assertEquals(created,detail);assertEquals(before+1,counter(o));
        assertFalse(detail.has("dataRevision"));assertFalse(detail.has("revision"));assertEquals(11,detail.size());
        var page=tree(request(o,get(c),null,200));assertEquals(1,page.path("total").asLong());assertFalse(page.path("items").get(0).has("content"));assertFalse(page.path("items").get(0).has("attachmentIds"));
        assertEquals(0,tree(request(o,get(collection(journal(o))),null,200)).path("total").asLong());
    }
    @Test void strictInputBoundsAndSessionIsolation()throws Exception {
        var o=owner();var other=owner();UUID j=journal(o);String c=collection(j);
        var created=tree(request(o,post(c),"{\"title\":\"\",\"content\":[]}",201));String d=D+"/"+id(created);
        request(other,get(d),null,404);request(other,get(c),null,404);request(other,post(c),"{\"title\":\"\",\"content\":[]}",404);
        assertEquals(401,mvc.perform(get(d)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post(c).session(o.session()).contentType("application/json").content("{\"title\":\"\",\"content\":[]}")).andReturn().getResponse().getStatus());
        for(String bad:List.of("{}","{\"title\":null,\"content\":[]}","{\"title\":1,\"content\":[]}","{\"title\":\"\",\"content\":{}}","{\"title\":\"\",\"title\":\"x\",\"content\":[]}","{\"title\":\"\",\"content\":[],\"authorId\":\""+o.user()+"\"}","{\"title\":\"\",\"content\":[]} {}","{\"title\":\""+"x".repeat(201)+"\",\"content\":[]}"))request(o,post(c),bad,400);
        request(o,post(c),"{\"title\":\"\",\"content\":[\""+"x".repeat(1048576)+"\"]}",413);
        request(o,post(c)," ".repeat(1114113),413);
        request(o,post(c),"{\"title\":\"\",\"content\":"+"[".repeat(101)+"0"+"]".repeat(101)+"}",400);
        request(o,get(c).param("limit","20","20"),null,400);request(o,get(c).param("workspaceId",other.workspace().toString()),null,400);
        request(o,get(D+"/1-1-1-1-1"),null,400);
        assertEquals(1L,jdbc.queryForObject("select count(*) from documents where author_id=?",Long.class,o.user()));
    }
    @Test void paginationOverOneHundredRowsBindsJournalLimitAndOwner()throws Exception {
        var o=owner();UUID j=journal(o),otherJournal=journal(o);UUID space=jdbc.queryForObject("select document_space_id from journals where id=?",UUID.class,j);
        jdbc.update("insert into documents(id,document_space_id,title,content,status,author_id,created_at,updated_at) select gen_random_uuid(),?,'Document '||n,'[]'::jsonb,'DRAFT',?,'2026-01-01Z','2026-01-01Z' from generate_series(1,105) n",space,o.user());
        var first=tree(request(o,get(collection(j)).param("limit","100"),null,200));assertEquals(105,first.path("total").asLong());assertEquals(100,first.path("items").size());String cursor=first.path("nextCursor").asString();
        var second=tree(request(o,get(collection(j)).param("limit","100").param("cursor",cursor),null,200));assertEquals(5,second.path("items").size());assertTrue(second.path("nextCursor").isNull());
        var ids=new HashSet<String>();first.path("items").forEach(n->assertTrue(ids.add(id(n))));second.path("items").forEach(n->assertTrue(ids.add(id(n))));assertEquals(105,ids.size());
        request(o,get(collection(j)).param("limit","99").param("cursor",cursor),null,400);request(o,get(collection(otherJournal)).param("limit","100").param("cursor",cursor),null,400);
        request(o,get(collection(j)).param("limit","100").param("cursor",cursor+"x"),null,400);
        var other=owner();request(other,get(collection(journal(other))).param("limit","100").param("cursor",cursor),null,400);
    }
    @Test void openApiHasOnlyImplementedOperationsAndOpaqueRequiredNullableContract()throws Exception {
        var o=owner();var response=mvc.perform(get("/v3/api-docs").session(o.session())).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString());var api=tree(response);var paths=api.path("paths");
        var collection=paths.path("/api/v2/journals/{journalId}/documents");assertTrue(collection.has("post"));assertTrue(collection.has("get"));
        assertTrue(paths.path("/api/v2/documents/{documentId}").has("get"));assertFalse(paths.has("/api/v2/documents"));assertFalse(paths.has("/api/v2/document-spaces"));
        var schemas=api.path("components").path("schemas");var snapshot=schemas.path("DocumentSnapshot");
        assertEquals("array",snapshot.path("properties").path("content").path("type").asString());
        assertTrue(snapshot.path("required").valueStream().anyMatch(n->n.asString().equals("publishedAt")));
        assertFalse(snapshot.path("properties").has("dataRevision"));
        assertTrue(collection.path("post").path("responses").path("201").path("headers").has("X-Workspace-Data-Revision"));
    }
}
