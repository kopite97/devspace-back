package com.kopite.devspace;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class JournalApiTests {
    private static final String BASE="/api/v2/journals";
    private final MockMvc mvc;
    private final UserWorkspaceCreationService users;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    @Autowired
    JournalApiTests(MockMvc mvc,UserWorkspaceCreationService users,JdbcTemplate jdbc,JsonMapper json) {
        this.mvc=mvc; this.users=users; this.jdbc=jdbc; this.json=json;
    }
    private record Owner(UUID user,UUID workspace,MockHttpSession session,String csrf) {}
    private Owner owner() throws Exception {
        var user=users.createOrReuse("journal-api",UUID.randomUUID().toString(),"Owner");
        var session=new MockHttpSession();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(new InternalUserPrincipal(user.user().getId(),"Owner"),null,List.of()));
        session.setAttribute("SPRING_SECURITY_CONTEXT",context);
        var token=json.readTree(mvc.perform(get("/api/v1/auth/csrf").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("csrfToken").asString();
        return new Owner(user.user().getId(),user.workspace().getId(),session,token);
    }
    private MockHttpServletRequestBuilder mutation(MockHttpServletRequestBuilder request,Owner owner) {
        return request.session(owner.session()).contentType("application/json").header("X-CSRF-Token",owner.csrf());
    }
    private final Map<UUID,UUID> categoryIds=new java.util.HashMap<>();
    private String category(Owner owner) {
        return categoryIds.computeIfAbsent(owner.workspace(),workspace->{UUID id=UUID.randomUUID();jdbc.update("insert into project_categories(id,workspace_id,name,created_at,updated_at) values(?,?,'Selected Category',now(),now())",id,workspace);return id;}).toString();
    }
    private String project(Owner owner) throws Exception {
        return json.readTree(mvc.perform(mutation(post("/api/v2/projects"),owner).header("Idempotency-Key",UUID.randomUUID().toString())
            .content("{\"name\":\"Project\",\"stack\":\"Java\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }
    private String body(String project) { return "{\"title\":\" Journal \",\"projectId\":\""+project+"\",\"body\":\" kept \\n body \",\"entryDate\":\"2024-02-29\"}"; }
    private JsonNode create(Owner owner,String body) throws Exception {
        return json.readTree(mvc.perform(mutation(post(BASE),owner).header("Idempotency-Key",UUID.randomUUID().toString()).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode read(Owner owner,String path,Map<String,String> params) throws Exception {
        var request=get(path).session(owner.session()); params.forEach(request::param);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andReturn().getResponse().getContentAsString());
    }


    @Test
    void fullLifecycleDateSemanticsAndLiveProjectData() throws Exception {
        var owner=owner(); String project=project(owner);
        var journal=create(owner,body(project)); String path=BASE+"/"+journal.get("id").asString();
        assertEquals(10,journal.size()); assertEquals("Journal",journal.get("title").asString());
        assertEquals(" kept \n body ",journal.get("body").asString());
        assertEquals("2024-02-29",journal.get("entryDate").asString());
        assertEquals(journal.get("createdAt"),journal.get("updatedAt"));
        assertEquals(journal,read(owner,path,Map.of()));
        mvc.perform(mutation(patch(path),owner).content("{\"revision\":1,\"entryDate\":\"2025-01-01\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.entryDate").value("2025-01-01"));
        mvc.perform(mutation(patch(path),owner).content("{\"revision\":1}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
        mvc.perform(mutation(patch("/api/v2/projects/"+project),owner).content("{\"revision\":1,\"name\":\"Renamed%_!\",\"categoryId\":\""+category(owner)+"\",\"status\":\"archived\"}"))
            .andExpect(status().isOk());
        var detail=read(owner,path,Map.of());
        assertEquals("Renamed%_!",detail.get("projectName").asString()); assertEquals(category(owner),detail.get("categoryId").asString());assertFalse(detail.has("scope"));
        assertEquals(journal.get("createdAt"),detail.get("createdAt")); assertEquals(2,detail.get("revision").asInt());
        assertEquals(1,read(owner,BASE,Map.of("query","%_!","category",category(owner),"projectStatus","archived")).get("total").asInt());
        assertEquals(0,read(owner,BASE,Map.of("projectStatus","active")).get("total").asInt());
        mvc.perform(mutation(patch(path),owner).content("{\"revision\":2}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
        mvc.perform(mutation(delete(path),owner).param("revision","3")).andExpect(status().isOk())
            .andExpect(jsonPath("$.deletedId").value(journal.get("id").asString())).andExpect(jsonPath("$.revision").doesNotExist());
        mvc.perform(get(path).session(owner.session())).andExpect(status().isNotFound());
        mvc.perform(mutation(patch(path),owner).content("{\"revision\":3}")).andExpect(status().isNotFound());
        mvc.perform(mutation(delete(path),owner).param("revision","3")).andExpect(status().isNotFound());
        assertEquals(0,read(owner,BASE,Map.of()).get("total").asInt());
    }

    @Test
    void strictWriteAndDateValidation() throws Exception {
        var owner=owner(); String project=project(owner);
        for(String value:List.of("{}",body(project).replace("Journal"," "),body(project).replace(project,"1-1-1-1-1"),
            body(project).replace("Journal","x".repeat(121))))
            mvc.perform(mutation(post(BASE),owner).header("Idempotency-Key","bad").content(value)).andExpect(status().isBadRequest());
        for(String field:List.of("title","projectId","body","entryDate")) {
            for(Object invalid:List.of(42,true,List.of())) {
                var fields=new LinkedHashMap<String,Object>(); fields.put("title","Journal");fields.put("projectId",project);fields.put("body","body");fields.put("entryDate","2024-02-29");
                fields.put(field,invalid);
                mvc.perform(mutation(post(BASE),owner).header("Idempotency-Key","bad").content(json.writeValueAsString(fields))).andExpect(status().isBadRequest());
            }
            mvc.perform(mutation(post(BASE),owner).header("Idempotency-Key","bad").content(body(project).replace("}",",\""+field+"\":null}"))).andExpect(status().isBadRequest());
        }
        for(String extra:List.of("\"scope\":\"server\"","\"projectName\":\"fallback\"","\"workspaceId\":\"ignored\"","\"userId\":\"ignored\"",
            "\"ownerUserId\":\"ignored\"","\"revision\":1","\"createdAt\":\"2024-02-29\"","\"updatedAt\":null","\"title\":\"duplicate\"","\"id\":\"client\""))
            mvc.perform(mutation(post(BASE),owner).header("Idempotency-Key","bad").content(body(project).replace("}",","+extra+"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(mutation(post(BASE),owner).content(body(project))).andExpect(status().isBadRequest());
        for(String date:List.of("2023-02-29","2024-2-29","0000-01-01","10000-01-01","2024-02-29T00:00:00Z","2024-13-01"," 2024-02-29")) {
            mvc.perform(mutation(post(BASE),owner).header("Idempotency-Key","bad").content(body(project).replace("2024-02-29",date))).andExpect(status().isBadRequest());
            for(String bound:List.of("from","to")) mvc.perform(get(BASE).session(owner.session()).param(bound,date)).andExpect(status().isBadRequest());
        }
        var journal=create(owner,body(project)); String path=BASE+"/"+journal.get("id").asString();
        for(String value:List.of("{}","{\"revision\":null}","{\"revision\":0}","{\"revision\":1.0}","{\"revision\":\"1\"}","{\"revision\":9007199254740992}",
            "{\"revision\":1,\"body\":null}","{\"revision\":1,\"body\":\"\"}","{\"revision\":1,\"title\":\" \"}","{\"revision\":1,\"entryDate\":\"2023-02-29\"}","{\"revision\":1,\"revision\":1}"))
            mvc.perform(mutation(patch(path),owner).content(value)).andExpect(status().isBadRequest());
        mvc.perform(mutation(delete(path),owner)).andExpect(status().isBadRequest());
        for(String rev:List.of("0","-1","9007199254740992","1.0")) mvc.perform(mutation(delete(path),owner).param("revision",rev)).andExpect(status().isBadRequest());
        for(var entry:Map.of("sort","bad","scope","bad","projectStatus","bad","limit","101","projectId","bad").entrySet())
            mvc.perform(get(BASE).session(owner.session()).param(entry.getKey(),entry.getValue())).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).session(owner.session()).param("from","2024-03-01").param("to","2024-02-29")).andExpect(status().isBadRequest());
    }

    @Test
    void securityIsolationAndReplayAfterPhysicalDeletion() throws Exception {
        var owner=owner(); var other=owner(); String project=project(owner);
        var journal=create(owner,body(project)); String path=BASE+"/"+journal.get("id").asString();
        for(var request:List.of(get(BASE),get(path),post(BASE),patch(path),delete(path)))
            mvc.perform(request.contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        for(var request:List.of(post(BASE),patch(path),delete(path)))
            mvc.perform(request.session(owner.session()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        for(var request:List.of(post(BASE),patch(path),delete(path)))
            mvc.perform(mutation(request,owner).header("Origin","https://evil.example").content(body(project))).andExpect(status().isForbidden());
        mvc.perform(post(BASE).session(owner.session()).header("X-CSRF-Token","invalid").contentType("application/json").content(body(project))).andExpect(status().isForbidden());
        for(String id:List.of(journal.get("id").asString(),UUID.randomUUID().toString())) {
            mvc.perform(get(BASE+"/"+id).session(other.session())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
            mvc.perform(mutation(patch(BASE+"/"+id),other).content("{\"revision\":99}")).andExpect(status().isNotFound());
            mvc.perform(mutation(delete(BASE+"/"+id),other).param("revision","99")).andExpect(status().isNotFound());
        }
        assertEquals(0,read(other,BASE,Map.of("query","Journal")).get("total").asInt());
        mvc.perform(get(BASE).session(other.session()).param("projectId",project).param("category",category(owner))).andExpect(status().isNotFound());
        mvc.perform(mutation(post(BASE),other).header("Idempotency-Key","foreign").content(body(project))).andExpect(status().isNotFound());
        String key=UUID.randomUUID().toString();
        var original=mvc.perform(mutation(post(BASE),owner).header("Idempotency-Key",key).content(body(project))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replayId=json.readTree(original).get("id").asString();
        mvc.perform(mutation(delete(BASE+"/"+replayId),owner).param("revision","1")).andExpect(status().isOk());
        Long before=jdbc.queryForObject("select data_revision from workspaces where id=?",Long.class,owner.workspace());
        mvc.perform(mutation(post(BASE),owner).header("Idempotency-Key",key).content(body(project))).andExpect(status().isCreated()).andExpect(content().json(original));
        assertEquals(before,jdbc.queryForObject("select data_revision from workspaces where id=?",Long.class,owner.workspace()));
        assertEquals(0L,jdbc.queryForObject("select count(*) from journals where id=?",Long.class,UUID.fromString(replayId)));
        jdbc.update("update users set disabled_at=now() where id=?",other.user());
        mvc.perform(get(BASE).session(other.session())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void threeKeyPaginationDatesLiteralSearchAndCursorBinding() throws Exception {
        var owner=owner(); String project=project(owner);
        for(int i=0;i<7;i++) JournalFixtures.insert(jdbc,"insert into journals(id,workspace_id,project_id,title,body,entry_date,created_at,updated_at) values(?,?,?,'Tied','Body %_! Needle',?::date,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),owner.workspace(),UUID.fromString(project),i<3?"2024-02-29":"2024-03-01",i%2==0?"2026-01-01T00:00:00Z":"2025-01-01T00:00:00Z","2026-01-01T00:00:00Z");
        List<String> newest=new ArrayList<>();
        for(String sort:List.of("newest","oldest")) {
            var page=read(owner,BASE,Map.of("limit","2","sort",sort)); List<String> seen=new ArrayList<>();
            while(true) {
                assertEquals(7,page.get("total").asInt());
                for(var item:page.get("items")) { assertTrue(item.has("body")); seen.add(item.get("id").asString()); }
                if(page.get("nextCursor").isNull()) break;
                page=read(owner,BASE,Map.of("limit","2","sort",sort,"cursor",page.get("nextCursor").asString()));
            }
            assertEquals(7,new HashSet<>(seen).size());
            List<String> expected=jdbc.queryForList("select id::text from journals where workspace_id=? order by entry_date "+(sort.equals("newest")?"desc":"asc")+",created_at "+(sort.equals("newest")?"desc":"asc")+",id "+(sort.equals("newest")?"desc":"asc"),String.class,owner.workspace());
            assertEquals(expected,seen);
            if(sort.equals("newest")) newest.addAll(seen); else assertEquals(newest.reversed(),seen);
        }
        assertEquals(3,read(owner,BASE,Map.of("from","2024-02-29","to","2024-02-29")).get("total").asInt());
        assertEquals(4,read(owner,BASE,Map.of("from","2024-03-01")).get("total").asInt());
        assertEquals(3,read(owner,BASE,Map.of("to","2024-02-29")).get("total").asInt());
        assertEquals(7,read(owner,BASE,Map.of("query","%_! needle")).get("total").asInt());
        assertEquals(0,read(owner,BASE,Map.of("query","not present")).get("total").asInt());
        String cursor=read(owner,BASE,Map.of("limit","2")).get("nextCursor").asString();
        for(var entry:Map.of("category",category(owner),"projectId",project,"projectStatus","active","from","2024-02-29","to","2024-03-01","sort","oldest","query","Tied","limit","3").entrySet()) {
            var request=get(BASE).session(owner.session()).param("limit","2").param("cursor",cursor);
            if(entry.getKey().equals("limit")) request=get(BASE).session(owner.session()).param("cursor",cursor);
            mvc.perform(request.param(entry.getKey(),entry.getValue())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }
        for(String invalid:List.of(cursor+"x","","junk")) mvc.perform(get(BASE).session(owner.session()).param("limit","2").param("cursor",invalid)).andExpect(status().isBadRequest());
        var other=owner();
        mvc.perform(get(BASE).session(other.session()).param("limit","2").param("cursor",cursor)).andExpect(status().isBadRequest());
        for(String resource:List.of("projects","tasks")) mvc.perform(get("/api/v2/"+resource).session(owner.session()).param("limit","2").param("cursor",cursor)).andExpect(status().isBadRequest());
        assertEquals(0,read(owner,BASE,Map.of("projectId",project,"category",category(owner))).get("total").asInt());
    }
}
