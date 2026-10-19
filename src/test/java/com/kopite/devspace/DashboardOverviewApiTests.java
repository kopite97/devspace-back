package com.kopite.devspace;
import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.junit.jupiter.api.*;
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
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class DashboardOverviewApiTests {
    static final String HOME="/api/v3/dashboards/home",OVERVIEW="/api/v2/overview";
    @Autowired MockMvc mvc;
    @Autowired UserWorkspaceCreationService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    record Owner(UUID user,UUID workspace,MockHttpSession session,String csrf) {}
    Owner owner() throws Exception {
        var u=users.createOrReuse("dashboard-api",UUID.randomUUID().toString(),"Owner");
        var session=new MockHttpSession();var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(new InternalUserPrincipal(u.user().getId(),"Owner"),null,List.of()));
        session.setAttribute("SPRING_SECURITY_CONTEXT",context);
        var token=json.readTree(mvc.perform(get("/api/v1/auth/csrf").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("csrfToken").asString();
        return new Owner(u.user().getId(),u.workspace().getId(),session,token);
    }
    MockHttpServletRequestBuilder putHome(Owner o,String body){return put(HOME).session(o.session()).contentType("application/json").header("X-CSRF-Token",o.csrf()).content(body);}
    JsonNode response(MockHttpServletRequestBuilder req) throws Exception {return json.readTree(mvc.perform(req).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());}
    JsonNode read(Owner o,String path) throws Exception{return response(get(path).session(o.session()));}
    String body(long revision,Object widgets){return json.writeValueAsString(Map.of("schemaVersion",3,"layoutRevision",revision,"placements",widgets));}
    Map<String,Object> widget(String type){return new LinkedHashMap<>(Map.of("id","widget","type",type,"title"," Title ","selection",Map.of("kind","all"),"size","wide"));}
    Set<String> keys(JsonNode n){return n.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());}
    UUID category(Owner o,String name) {
        var existing=jdbc.queryForList("select id from project_categories where workspace_id=? and name=?",UUID.class,o.workspace(),name);if(!existing.isEmpty())return existing.getFirst();
        UUID id=UUID.randomUUID();jdbc.update("insert into project_categories(id,workspace_id,name,created_at,updated_at) values(?,?,?,now(),now())",id,o.workspace(),name);return id;
    }
    UUID project(Owner o,String categoryName,boolean archived) {
        UUID p=UUID.randomUUID();jdbc.update("insert into projects(id,workspace_id,name,category_id,stack,status,created_at,updated_at) values(?,?,'Project',?,'Java',?,now(),now())",p,o.workspace(),category(o,categoryName),archived?"archived":"active");return p;
    }
    // Widget and placement contracts are covered by WidgetApiTests and CategoryOnlyDashboardApiTests.
    @Test void overviewExactZeroShapeTotalsArchivedAndIntersection() throws Exception {
        var o=owner();var empty=read(o,OVERVIEW);
        assertEquals(Set.of("category","projectId","projects","tasks","asOf","externalDataUnavailable"),keys(empty));assertTrue(empty.get("projectId").isNull());assertEquals("all",empty.get("category").asString());
        assertEquals(Set.of("total","archived","byCategory"),keys(empty.get("projects")));assertEquals(1,empty.path("projects").path("byCategory").size());assertTrue(empty.path("projects").path("byCategory").get(0).path("categoryId").isNull());assertEquals(Set.of("todo","doing","done","total"),keys(empty.get("tasks")));
        UUID active=project(o,"unity",false),archived=project(o,"server",true);
        for(String status:List.of("todo","doing","done"))jdbc.update("insert into tasks(id,workspace_id,project_id,title,status,created_at,updated_at) values(?,?,?,'Task',?,now(),now())",UUID.randomUUID(),o.workspace(),archived,status);
        jdbc.update("insert into tasks(id,workspace_id,project_id,title,deleted_at,created_at,updated_at) values(?,?,?,'Trash',now(),now(),now())",UUID.randomUUID(),o.workspace(),active);
        var all=read(o,OVERVIEW);assertEquals(1,all.get("projects").get("total").asLong());assertEquals(1,all.get("projects").get("archived").asLong());assertEquals(3,all.get("tasks").get("total").asLong());
        var selected=response(get(OVERVIEW).session(o.session()).param("projectId",archived.toString()));assertEquals(0,selected.get("projects").get("total").asLong());assertEquals(1,selected.get("projects").get("archived").asLong());
        var mismatch=response(get(OVERVIEW).session(o.session()).param("projectId",archived.toString()).param("category",category(o,"unity").toString()));assertEquals(0,mismatch.get("tasks").get("total").asLong());assertEquals(0,mismatch.get("projects").get("archived").asLong());
        var stats=read(o,"/api/v2/tasks/stats");assertEquals(stats.get("total"),all.get("tasks").get("total"));for(String status:List.of("todo","doing","done"))assertEquals(stats.get("counts").get(status),all.get("tasks").get(status));
        assertEquals(0,read(o,HOME).get("layoutRevision").asLong());
    }
    @Test void strictQueriesAndAuthorization() throws Exception {
        Owner o=owner(),other=owner();UUID foreign=project(other,"server",true);
        for(String path:List.of(HOME,OVERVIEW)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for(String key:List.of("limit","cursor","query","status","projectStatus","deleted","workspaceId"))
                mvc.perform(get(path).session(o.session()).param(key,"x")).andExpect(status().isBadRequest());
        }
        for(String scope:List.of("","ALL","bad"))mvc.perform(get(OVERVIEW).session(o.session()).param("scope",scope)).andExpect(status().isBadRequest());
        mvc.perform(get(OVERVIEW).session(o.session()).param("scope","all","unity")).andExpect(status().isBadRequest());
        mvc.perform(get(OVERVIEW).session(o.session()).param("projectId",foreign.toString(),foreign.toString())).andExpect(status().isBadRequest());
        for(String id:List.of("","bad","1-1-1-1-1"))mvc.perform(get(OVERVIEW).session(o.session()).param("projectId",id)).andExpect(status().isBadRequest());
        for(UUID id:List.of(foreign,UUID.randomUUID())) {
            mvc.perform(get(OVERVIEW).session(o.session()).param("projectId",id.toString()).param("category",category(o,"unity").toString())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
            mvc.perform(putHome(o,body(0,List.of(Map.of("widgetId",id.toString(),"size","wide"))))).andExpect(status().isNotFound());
        }
        mvc.perform(put(HOME).contentType("application/json").content(body(0,List.of()))).andExpect(status().isUnauthorized());
        mvc.perform(put(HOME).session(o.session()).contentType("application/json").content(body(0,List.of()))).andExpect(status().isForbidden());
        mvc.perform(put(HOME).session(o.session()).header("X-CSRF-Token","bad").contentType("application/json").content(body(0,List.of()))).andExpect(status().isForbidden());
        mvc.perform(putHome(o,body(0,List.of())).header("Origin","https://evil.invalid")).andExpect(status().isForbidden());
        mvc.perform(putHome(o,body(0,List.of())).param("scope","all")).andExpect(status().isBadRequest());
        response(putHome(o,body(0,List.of())));assertEquals(0,read(other,HOME).get("layoutRevision").asLong());assertEquals(0,read(o,OVERVIEW).get("projects").get("total").asLong());
        for(String path:List.of(HOME,OVERVIEW)) {
            var disabled=owner();jdbc.update("update users set disabled_at=now() where id=?",disabled.user());
            mvc.perform(get(path).session(disabled.session())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        }
        var disabled=owner();jdbc.update("update users set disabled_at=now() where id=?",disabled.user());
        mvc.perform(putHome(disabled,body(0,List.of()))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }
    @RepeatedTest(3) void concurrentFirstHttpPutReturns200And409() throws Exception {
        var o=owner();CyclicBarrier start=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> save=()->{start.await(10,TimeUnit.SECONDS);return mvc.perform(putHome(o,body(0,List.of()))).andReturn().getResponse().getStatus();};
            var a=pool.submit(save);var b=pool.submit(save);assertEquals(Set.of(200,409),Set.of(a.get(25,TimeUnit.SECONDS),b.get(25,TimeUnit.SECONDS)));
        }
        assertEquals(1,read(o,HOME).get("layoutRevision").asLong());assertEquals(1L,jdbc.queryForObject("select data_revision from workspaces where id=?",Long.class,o.workspace()));
    }
}
