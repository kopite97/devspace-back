package com.kopite.devspace;
import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @ActiveProfiles("test") @Import(TestcontainersConfiguration.class) @AutoConfigureMockMvc
class CategoryOnlyQueryApiTests {
    @Autowired MockMvc mvc; @Autowired UserWorkspaceCreationService users; @Autowired JdbcTemplate jdbc; @Autowired JsonMapper json;
    @Value("${app.security.origin}") String origin;
    record Owner(UUID user,UUID workspace,MockHttpSession session,String csrf) {}
    Owner owner()throws Exception {
        var o=users.createOrReuse("v2-replay",UUID.randomUUID().toString(),"Owner");
        var ctx=SecurityContextHolder.createEmptyContext();ctx.setAuthentication(new UsernamePasswordAuthenticationToken(new InternalUserPrincipal(o.user().getId(),"Owner"),null,List.of()));
        var session=new MockHttpSession();session.setAttribute("SPRING_SECURITY_CONTEXT",ctx);
        String csrf=json.readTree(mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).path("csrfToken").asString();
        return new Owner(o.user().getId(),o.workspace().getId(),session,csrf);
    }
    String post(Owner o,String path,String key,String body,int status)throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).session(o.session()).header("X-CSRF-Token",o.csrf()).header("Origin",origin)
            .header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().is(status))
            .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
    }

    tools.jackson.databind.JsonNode read(Owner o,String path)throws Exception {
        var response=mvc.perform(get(path).session(o.session())).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("X-Workspace-Data-Revision",Long.toString(counter(o)))).andReturn().getResponse();
        return json.readTree(response.getContentAsString());
    }
    long counter(Owner o){return jdbc.queryForObject("select data_revision from workspaces where id=?",Long.class,o.workspace());}
    String category(Owner o,String name)throws Exception{return json.readTree(post(o,"/api/v1/project-categories",name,json.writeValueAsString(Map.of("name",name)),201)).path("id").asString();}
    String project(Owner o,String name,String category)throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("name",name);body.put("stack","Java");body.put("categoryId",category);
        return json.readTree(post(o,"/api/v2/projects",name,json.writeValueAsString(body),201)).path("id").asString();
    }
    void patchProject(Owner o,String id,long revision,Object category)throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("revision",revision);body.put("categoryId",category);
        long before=counter(o);
        mvc.perform(patch("/api/v2/projects/"+id).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf())
            .contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk())
            .andExpect(header().string("X-Workspace-Data-Revision",Long.toString(before+1)));
    }
    void children(Owner o,String project)throws Exception {
        var id=UUID.fromString(project);
        jdbc.update("insert into tasks(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'Needle',now(),now())",UUID.randomUUID(),o.workspace(),id);
        JournalFixtures.insert(jdbc,"insert into journals(id,workspace_id,project_id,title,body,entry_date,created_at,updated_at) values(?,?,?,'Needle','Body',current_date,now(),now())",UUID.randomUUID(),o.workspace(),id);
        jdbc.update("insert into milestones(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'Needle',now(),now())",UUID.randomUUID(),o.workspace(),id);
    }
    @Test void filtersIntersectionsOwnershipAndStrictQueries()throws Exception {
        var o=owner();var other=owner();String a=category(o,"A"),b=category(o,"B"),foreign=category(other,"A");
        String p=project(o,"Needle",a),p2=project(o,"Second",b),p3=project(o,"Third",null);children(o,p);children(o,p2);children(o,p3);
        for(String resource:List.of("projects","tasks","journals","milestones")) {
            String path="/api/v2/"+resource;
            assertEquals(3,read(o,path).path("total").asInt());
            assertEquals(1,read(o,path+"?category="+a.toUpperCase(Locale.ROOT)).path("total").asInt());
            assertEquals(1,read(o,path+"?category=uncategorized").path("total").asInt());
            if(!resource.equals("projects"))assertEquals(0,read(o,path+"?category="+a+"&projectId="+p2).path("total").asInt());
            for(String bad:List.of("category=","category=null","category=unity","scope=all","category=all&category=all"))
                mvc.perform(get(path+"?"+bad).session(o.session())).andExpect(status().isBadRequest()).andExpect(header().doesNotExist("X-Workspace-Data-Revision"));
            for(String bad:List.of(foreign,UUID.randomUUID().toString()))mvc.perform(get(path+"?category="+bad).session(o.session())).andExpect(status().isNotFound());
        }
        for(String path:List.of("/api/v2/tasks/stats","/api/v2/overview")) {
            for(String bad:List.of("category=","category=null","scope=all","category=all&category=all"))mvc.perform(get(path+"?"+bad).session(o.session())).andExpect(status().isBadRequest());
            mvc.perform(get(path+"?category="+foreign).session(o.session())).andExpect(status().isNotFound());
        }
        assertEquals(0,read(o,"/api/v2/tasks/stats?category="+a+"&projectId="+p2).path("total").asInt());
        assertEquals(1,read(o,"/api/v2/projects?category="+a+"&query=needle").path("total").asInt());
        assertEquals(1,read(o,"/api/v2/journals?category="+a+"&sort=oldest&query=needle").path("total").asInt());
    }
    @Test void liveCategoryMetadataCountsAndResourceRevisions()throws Exception {
        var o=owner();String a=category(o,"A"),empty=category(o,"Empty"),p=project(o,"Project",null);children(o,p);
        var before=new LinkedHashMap<String,tools.jackson.databind.JsonNode>();
        for(String r:List.of("tasks","journals","milestones"))before.put(r,read(o,"/api/v2/"+r).path("items").get(0));
        patchProject(o,p,1,a);
        for(var entry:before.entrySet()) {
            var after=read(o,"/api/v2/"+entry.getKey()+"/"+entry.getValue().path("id").asString());
            assertEquals(a,after.path("categoryId").asString());assertFalse(after.has("scope"));assertFalse(after.has("categoryName"));
            assertEquals(entry.getValue().path("revision"),after.path("revision"));assertEquals(entry.getValue().path("updatedAt"),after.path("updatedAt"));
        }
        long beforeRename=counter(o);
        mvc.perform(patch("/api/v1/project-categories/"+a).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf())
            .contentType("application/json").content(json.writeValueAsString(Map.of("revision",1,"name","Renamed")))).andExpect(status().isOk())
            .andExpect(header().string("X-Workspace-Data-Revision",Long.toString(beforeRename+1)));
        assertEquals("Renamed",read(o,"/api/v1/project-categories/"+a).path("name").asString());
        assertEquals(2,read(o,"/api/v2/projects/"+p).path("revision").asInt());
        var overview=read(o,"/api/v2/overview");assertEquals(3,overview.path("projects").path("byCategory").size());assertEquals(1,overview.path("projects").path("total").asInt());
        var counts=read(o,"/api/v2/projects/category-counts");assertEquals(3,counts.path("items").size());assertTrue(counts.path("items").get(2).path("categoryId").isNull());
        assertEquals(a,counts.path("items").get(0).path("categoryId").asString());assertEquals(empty,counts.path("items").get(1).path("categoryId").asString());
        assertEquals(0,counts.path("items").get(1).path("active").asInt());assertEquals(1,counts.path("totals").path("active").asInt());
        patchProject(o,p,2,null);
        assertEquals(1,read(o,"/api/v2/tasks?category=uncategorized").path("total").asInt());
        assertEquals(0,read(o,"/api/v2/tasks/stats?category="+a).path("total").asInt());
        jdbc.update("update workspaces set data_revision=? where id=?",Long.MAX_VALUE,o.workspace());read(o,"/api/v2/projects/"+p);read(o,"/api/v1/project-categories");
    }
    @Test void cursorsBindCategoryAndRetainLiveKeysetAcrossReclassification()throws Exception {
        var o=owner();var other=owner();String a=category(o,"A"),b=category(o,"B");
        for(int i=0;i<3;i++)children(o,project(o,"P"+i,a));
        for(String r:List.of("projects","tasks","journals","milestones")) {
            String path="/api/v2/"+r,query="?category="+a+"&limit=1";
            var first=read(o,path+query);String cursor=first.path("nextCursor").asString();assertTrue(cursor.startsWith("v2."));
            var next=read(o,path+query+"&cursor="+cursor);assertNotEquals(first.path("items").get(0).path("id"),next.path("items").get(0).path("id"));
            for(String invalid:List.of("?category="+b+"&limit=1&cursor="+cursor,query+"&cursor="+cursor.replaceFirst("v2","v1"),"?category="+a+"&limit=2&cursor="+cursor))
                mvc.perform(get(path+invalid).session(o.session())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
            // Workspace freshness is not part of cursor identity.
            category(o,"Unused-"+r);read(o,path+query+"&cursor="+cursor);
            mvc.perform(get(path+query+"&cursor="+cursor.substring(0,cursor.length()-3)+"xxx").session(o.session())).andExpect(status().isBadRequest());
            var allCursor=read(o,path+"?limit=1").path("nextCursor").asString();
            mvc.perform(get(path+"?limit=1&cursor="+allCursor).session(other.session())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
            if(r.equals("journals"))mvc.perform(get(path+query+"&sort=oldest&cursor="+cursor).session(o.session())).andExpect(status().isBadRequest());
        }
        var page=read(o,"/api/v2/projects?category="+a+"&limit=1");String id=page.path("items").get(0).path("id").asString();
        patchProject(o,id,1,b);
        assertEquals(2,read(o,"/api/v2/projects?category="+a+"&limit=1&cursor="+page.path("nextCursor").asString()).path("total").asInt());
    }

    @Test void archiveTrashDateOrderingLiteralSearchAndReplayHeaders()throws Exception {
        var o=owner();String a=category(o,"A"),p=project(o,"Percent%_!",a);children(o,p);
        mvc.perform(patch("/api/v2/projects/"+p).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision",1,"status","archived")))).andExpect(status().isOk());
        assertEquals(0,read(o,"/api/v2/projects?category="+a).path("total").asInt());
        assertEquals(1,read(o,"/api/v2/projects?status=archived&category="+a).path("total").asInt());
        assertEquals(1,read(o,"/api/v2/tasks?category="+a).path("total").asInt());
        assertEquals(0,read(o,"/api/v2/tasks?category="+a+"&projectStatus=active").path("total").asInt());
        var task=read(o,"/api/v2/tasks").path("items").get(0);
        mvc.perform(delete("/api/v2/tasks/"+task.path("id").asString()).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf()).queryParam("revision","1"))
            .andExpect(status().isOk()).andExpect(header().string("X-Workspace-Data-Revision",Long.toString(counter(o))));
        assertEquals(1,read(o,"/api/v2/tasks?category="+a+"&deleted=true").path("total").asInt());
        assertEquals(0,read(o,"/api/v2/tasks/stats?category="+a).path("total").asInt());
        for(String invalid:List.of("deleted=true","status=todo"))mvc.perform(get("/api/v2/tasks/stats?"+invalid).session(o.session())).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/tasks/"+task.path("id").asString()+"/restore").session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf())
            .contentType("application/json").content(json.writeValueAsString(Map.of("revision",2)))).andExpect(status().isOk());
        patchProject(o,p,2,null);assertEquals(1,read(o,"/api/v2/tasks?category=uncategorized").path("total").asInt());
        jdbc.update("update journals set entry_date='2024-02-29' where workspace_id=?",o.workspace());
        UUID earlier=UUID.randomUUID();JournalFixtures.insert(jdbc,"insert into journals(id,workspace_id,project_id,title,body,entry_date,created_at,updated_at) values(?,?,?,'Earlier','Body','2024-02-28',now(),now())",earlier,o.workspace(),UUID.fromString(p));
        assertEquals(1,read(o,"/api/v2/journals?from=2024-02-29&to=2024-02-29").path("total").asInt());
        assertEquals(earlier.toString(),read(o,"/api/v2/journals?sort=oldest&limit=1").path("items").get(0).path("id").asString());
        mvc.perform(get("/api/v2/journals?from=2024-03-01&to=2024-02-29").session(o.session())).andExpect(status().isBadRequest());
        jdbc.update("update journals set entry_date='2024-02-29',created_at='2024-02-29T00:00:00Z' where workspace_id=?",o.workspace());
        for(String sort:List.of("oldest","newest")) {
            var expected=jdbc.queryForList("select id from journals where workspace_id=? order by id "+(sort.equals("oldest")?"asc":"desc"),UUID.class,o.workspace());
            var first=read(o,"/api/v2/journals?sort="+sort+"&limit=1");assertEquals(expected.get(0).toString(),first.path("items").get(0).path("id").asString());
            var second=read(o,"/api/v2/journals?sort="+sort+"&limit=1&cursor="+first.path("nextCursor").asString());assertEquals(expected.get(1).toString(),second.path("items").get(0).path("id").asString());
        }
        UUID dated=UUID.randomUUID();jdbc.update("insert into milestones(id,workspace_id,project_id,title,due_date,created_at,updated_at) values(?,?,?,'Dated','2024-02-29',now(),now())",dated,o.workspace(),UUID.fromString(p));
        assertEquals(dated.toString(),read(o,"/api/v2/milestones?limit=1").path("items").get(0).path("id").asString());
        jdbc.update("update milestones set completed=true where id=?",dated);
        assertEquals(1,read(o,"/api/v2/milestones?status=done").path("total").asInt());assertEquals(2,read(o,"/api/v2/milestones?status=all").path("total").asInt());
        for(String resource:List.of("projects","tasks","journals"))
            mvc.perform(get("/api/v2/"+resource+(resource.equals("projects")?"?status=all":"")).queryParam("query","%_!").session(o.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(0)));
        String intent=json.writeValueAsString(Map.of("name","Replay","stack","Java"));long previous=counter(o);
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/projects").session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf()).header("Idempotency-Key","header-replay").contentType("application/json").content(intent);
        String body=mvc.perform(request).andExpect(status().isCreated()).andExpect(header().string("X-Workspace-Data-Revision",Long.toString(previous+1))).andReturn().getResponse().getContentAsString();
        mvc.perform(request).andExpect(status().isCreated()).andExpect(header().doesNotExist("X-Workspace-Data-Revision")).andExpect(content().string(body));
        assertEquals(previous+1,counter(o));
    }
}
