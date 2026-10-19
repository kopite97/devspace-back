package com.kopite.devspace;
import com.kopite.devspace.widget.application.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class WidgetApiTests extends WidgetTestSupport {
    @Test void configurationLayoutIdentityAndIndependentRevisions()throws Exception {
        var o=owner();var empty=tree(request(o,get(D),null,200));assertFalse(empty.path("initialized").asBoolean());assertEquals(0,counter(o));
        var a=create(o,"board");var b=create(o,"links");assertEquals(2,counter(o));
        var first=tree(request(o,put(D),layout(0,id(a),id(b)),200));assertEquals(1,first.path("layoutRevision").asLong());assertEquals(2,first.path("widgets").size());
        String pa=first.path("placements").get(0).path("id").asString();String pb=first.path("placements").get(1).path("id").asString();
        var edited=tree(request(o,put(W+"/"+id(a)),update(1),200));assertEquals(2,edited.path("revision").asLong());
        var reordered=tree(request(o,put(D),layout(1,id(b),id(a)),200));assertEquals(pb,reordered.path("placements").get(0).path("id").asString());assertEquals(pa,reordered.path("placements").get(1).path("id").asString());
        assertEquals(id(b),id(reordered.path("widgets").get(0)));assertEquals(id(a),id(reordered.path("widgets").get(1)));
        assertEquals(409,request(o,put(W+"/"+id(a)),update(1),409).getStatus());
        request(o,delete(W+"/"+id(a)+"?revision=2"),null,409);
        request(o,put(D),layout(2),200);request(o,delete(W+"/"+id(a)+"?revision=2"),null,200);request(o,delete(W+"/"+id(a)+"?revision=2"),null,404);
        assertEquals(7,counter(o));
    }
    @Test void historicalCreationReplaySurvivesDeleteAndExpiry()throws Exception {
        var o=owner();String body=createBody("board");var first=request(o,post(W).header("Idempotency-Key","same"),body,201);String id=id(tree(first));
        request(o,put(W+"/"+id),update(1),200);request(o,delete(W+"/"+id+"?revision=2"),null,200);
        long revision=counter(o);var replay=request(o,post(W).header("Idempotency-Key","same"),body,201);
        assertEquals(first.getContentAsString(),replay.getContentAsString());assertEquals(first.getHeader("Location"),replay.getHeader("Location"));assertNull(replay.getHeader("X-Workspace-Data-Revision"));assertEquals(revision,counter(o));request(o,get(W+"/"+id),null,404);
        request(o,post(W).header("Idempotency-Key","same"),body.replace("Example","Other"),409);
        jdbc.update("update widget_operation_replays set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where workspace_id=?",o.workspace());
        var fresh=tree(request(o,post(W).header("Idempotency-Key","same"),body,201));assertNotEquals(id,id(fresh));assertEquals(revision+1,counter(o));
    }
    @Test void initializationAndExplicitEmptyAreDistinctAndReplayPrecedesConflict()throws Exception {
        var o=owner();String body="{\"schemaVersion\":3,\"layoutRevision\":0}";
        var initial=request(o,post(I).header("Idempotency-Key","init"),body,201);var n=tree(initial);assertEquals(6,n.path("placements").size());assertEquals(6,n.path("widgets").size());assertEquals(1,counter(o));
        request(o,put(D),layout(1),200);var again=request(o,post(I).header("Idempotency-Key","init"),body,201);assertEquals(initial.getContentAsString(),again.getContentAsString());assertNull(again.getHeader("X-Workspace-Data-Revision"));assertEquals(2,counter(o));
        request(o,post(I).header("Idempotency-Key","other"),body,409);
        var empty=owner();var saved=tree(request(empty,put(D),layout(0),200));assertTrue(saved.path("initialized").asBoolean());assertEquals(1,saved.path("layoutRevision").asLong());assertEquals(0,saved.path("widgets").size());request(empty,post(I).header("Idempotency-Key","init"),body,409);
        assertEquals(0,jdbc.queryForObject("select count(*) from widgets where workspace_id=?",Integer.class,empty.workspace()));
    }
    @Test void strictInputsSecurityTenantIsolationAndRetirement()throws Exception {
        var o=owner();var other=owner();var a=create(o,"board");String path=W+"/"+id(a);
        request(other,get(path),null,404);request(other,put(D),layout(0,id(a)),404);
        assertEquals(401,mvc.perform(get(W)).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(post(W).session(o.session()).contentType("application/json").content(createBody("board"))).andReturn().getResponse().getStatus());
        for(String bad:List.of(createBody("board").replace("\"title\":\"Example\"","\"title\":\"Example\",\"title\":\"Other\""),createBody("board").replace("\"kind\":\"all\"","\"kind\":\"all\",\"kind\":\"all\""),createBody("board").replace("\"configVersion\":1","\"configVersion\":1.0"),createBody("board").replace("\"title\":\"Example\"","\"referenceState\":\"valid\""),createBody("board")+" {}"))request(o,post(W).header("Idempotency-Key",UUID.randomUUID().toString()),bad,400);
        request(o,delete(path+"?revision=1&revision=1"),null,400);request(o,delete(path+"?revision=1"),"{}",400);
        request(o,put(D),"{\"schemaVersion\":3,\"layoutRevision\":0,\"placements\":[{\"widgetId\":\""+id(a)+"\",\"size\":\"wide\",\"id\":\""+UUID.randomUUID()+"\"}]}",400);
        request(o,get("/api/v2/dashboards/home"),null,410);request(o,put("/api/v2/dashboards/home"),"{}",410);assertEquals(1,counter(o));
    }
    @Test void keysetListsAndFilterBoundCursors()throws Exception {
        var o=owner();create(o,"board");create(o,"board");create(o,"board");var page=tree(request(o,get(W+"?limit=2"),null,200));assertEquals(2,page.path("items").size());String cursor=page.path("nextCursor").asString();
        assertEquals(1,tree(request(o,get(W).param("limit","2").param("cursor",cursor),null,200)).path("items").size());
        request(o,get(W).param("limit","3").param("cursor",cursor),null,400);request(owner(),get(W).param("limit","2").param("cursor",cursor),null,400);
        request(o,put(D),layout(0,id(page.path("items").get(0))),200);assertEquals(2,tree(request(o,get(W+"?unplaced=true"),null,200)).path("items").size());
    }
    @Test void retainedCategoryAndBrokenProjectKeepAsymmetricContract()throws Exception {
        var o=owner();UUID category=UUID.randomUUID();jdbc.update("insert into project_categories(id,workspace_id,name,revision,created_at,updated_at) values(?,?,'Category',1,now(),now())",category,o.workspace());
        String body=createBody("board").replace("\"kind\":\"all\"","\"kind\":\"category\",\"categoryId\":\""+category+"\"");
        String id=id(tree(request(o,post(W).header("Idempotency-Key","category"),body,201)));jdbc.update("delete from project_categories where id=?",category);
        assertEquals("missingCategory",tree(request(o,get(W+"/"+id),null,200)).path("referenceState").asString());
        request(o,put(W+"/"+id),update(1).replace("\"kind\":\"all\"","\"kind\":\"category\",\"categoryId\":\""+category+"\""),200);
        request(o,post(W).header("Idempotency-Key","new"),body,404);
        jdbc.update("update widgets set config=?::jsonb where id=?","{\"selection\":{\"kind\":\"project\",\"projectId\":\""+UUID.randomUUID()+"\"}}",UUID.fromString(id));request(o,get(W+"/"+id),null,404);
    }
    @Test void overflowAndRawFingerprintRules()throws Exception {
        assertEquals("b311d35dafc88f0b20e0ae705f819a8c67f42892f46e4f9de8a832c9eb3d85c5",WidgetRequestHash.of(I,"{\"schemaVersion\":3,\"layoutRevision\":0}"));
        for(String type:List.of("overview","board","deploy","links","journal","milestone")){
            String b=createBody(type);assertEquals(WidgetRequestHash.of(W,b),WidgetRequestHash.of(W,WidgetJson.canonical(WidgetJson.parse(b))));assertNotEquals(WidgetRequestHash.of(W,b),WidgetRequestHash.of(W,b.replace("Example"," Example ")));}
        var o=owner();var a=create(o,"board");jdbc.update("update workspaces set data_revision=9223372036854775807 where id=?",o.workspace());request(o,put(W+"/"+id(a)),update(1),409);assertEquals(1,tree(request(o,get(W+"/"+id(a)),null,200)).path("revision").asLong());
    }
    @Test void openApiPublishesVersionedContractsAndHeaders()throws Exception {
        var o=owner();var result=mvc.perform(get("/v3/api-docs").session(o.session())).andReturn();assertNull(result.getResolvedException(),()->Arrays.toString(result.getResolvedException().getStackTrace()));var response=result.getResponse();assertEquals(200,response.getStatus(),response.getContentAsString());var api=json.readTree(response.getContentAsString());
        for(String path:List.of(W,W+"/{id}",W+"/{id}/data",D,I,"/api/v1/widget-types","/api/v2/dashboards/home"))assertTrue(api.path("paths").has(path),path);
        assertTrue(api.path("paths").path(W).path("post").path("responses").path("201").path("headers").has("Location"));
        assertTrue(api.path("paths").path(D).path("get").path("responses").path("200").path("headers").has("X-Workspace-Data-Revision"));
        assertEquals(6,api.path("components").path("schemas").path("CreateWidgetRequest").path("oneOf").size());
        assertTrue(api.path("components").path("schemas").has("BoardWidgetData"));
        assertFalse(api.path("components").path("schemas").path("WidgetDataEnvelope").path("properties").has("dataRevision"));
        var schemas=api.path("components").path("schemas");
        for(String field:List.of("data","page","problem")) {
            var s=schemas.path("WidgetDataEnvelope").path("properties").path(field);assertFalse(s.has("$ref"));assertFalse(s.has("type"));assertEquals(2,s.path("anyOf").size());
            assertEquals("null",s.path("anyOf").get(1).path("type").asString());
        }
        assertEquals("#/components/schemas/BoardWidgetData",schemas.path("WidgetPayload").path("discriminator").path("mapping").path("board").asString());
        assertEquals(14,schemas.path("TaskSnapshot").path("required").size());
        assertTrue(schemas.path("TaskSnapshot").path("properties").path("categoryId").path("type").valueStream().anyMatch(v->v.asString().equals("null")));
    }
    @Test void exportCompleteExamplesFromRealHttpResponses()throws Exception {
        var o=owner();var examples=new LinkedHashMap<String,Object>();var types=new ArrayList<Object>();var ids=new ArrayList<String>();
        examples.put("unsavedLayout",tree(request(o,get(D),null,200)));
        for(String type:List.of("overview","board","deploy","links","journal","milestone")) {
            var created=request(o,post(W).header("Idempotency-Key","example-"+type),createBody(type),201);var widget=tree(created);ids.add(id(widget));
            types.add(Map.of("createRequest",json.readTree(createBody(type)),"createStatus",201,"location",created.getHeader("Location"),"createResponse",widget,"dataResponse",tree(request(o,get(W+"/"+id(widget)+"/data"),null,200))));
        }
        examples.put("types",types);examples.put("layoutPutRequest",json.readTree(layout(0,ids.toArray(String[]::new))));examples.put("savedLayout",tree(request(o,put(D),layout(0,ids.toArray(String[]::new)),200)));
        var output=java.nio.file.Path.of("build/reports/plan0013/http-examples.json");java.nio.file.Files.createDirectories(output.getParent());java.nio.file.Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(examples));
    }
}
