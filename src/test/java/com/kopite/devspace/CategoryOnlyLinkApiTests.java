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
class CategoryOnlyLinkApiTests {
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

    tools.jackson.databind.JsonNode link(Owner o,String key,String project)throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("label","Link");body.put("url","https://example.com");body.put("projectId",project);
        return json.readTree(post(o,"/api/v2/links",key,json.writeValueAsString(body),201));
    }
    @Test void projectRelationsDerivedCategoryArchiveAndGlobalCollectionRemainIndependent()throws Exception {
        var o=owner();var other=owner();String a=category(o,"A"),b=category(o,"B"),p=project(o,"Project",a),u=project(o,"Unclassified",null),foreign=project(other,"Foreign",null);
        mvc.perform(patch("/api/v2/projects/"+p).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision",1,"status","archived")))).andExpect(status().isOk());
        var linked=link(o,"linked",p);var unclassified=link(o,"unclassified",u);var unlinked=link(o,"unlinked",null);
        String id=linked.path("item").path("id").asString();
        assertEquals(a,linked.path("item").path("categoryId").asString());assertEquals("Project",linked.path("item").path("projectName").asString());
        assertEquals(3,read(o,"/api/v2/links").path("total").asInt());assertEquals(1,read(o,"/api/v2/links?category="+a).path("total").asInt());
        assertEquals(2,read(o,"/api/v2/links?category=uncategorized").path("total").asInt());
        assertEquals(1,read(o,"/api/v2/links?projectStatus=active").path("total").asInt());assertEquals(1,read(o,"/api/v2/links?projectStatus=archived").path("total").asInt());
        assertEquals(0,read(o,"/api/v2/links?projectId="+p+"&category="+b).path("total").asInt());
        mvc.perform(get("/api/v2/links?projectId="+foreign).session(o.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/links?category="+UUID.randomUUID()).session(o.session())).andExpect(status().isNotFound());
        patchProject(o,p,2,b);
        var current=read(o,"/api/v2/links/"+id);assertEquals(1,current.path("revision").asInt());assertEquals(b,current.path("categoryId").asString());
        assertEquals(linked.path("item").path("updatedAt"),current.path("updatedAt"));assertEquals(3,read(o,"/api/v2/links").path("collectionRevision").asInt());
        mvc.perform(patch("/api/v2/projects/"+p).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision",3,"name","Fresh Project")))).andExpect(status().isOk());
        assertEquals("Fresh Project",read(o,"/api/v2/links/"+id).path("projectName").asString());
        mvc.perform(patch("/api/v1/project-categories/"+b).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision",1,"name","Renamed")))).andExpect(status().isOk());
        assertEquals(1,read(o,"/api/v2/links/"+id).path("revision").asInt());assertEquals(3,read(o,"/api/v2/links").path("collectionRevision").asInt());
        assertEquals(linked,link(o,"linked",p));
        var clear=new LinkedHashMap<String,Object>();clear.put("revision",1);clear.put("projectId",null);
        long before=counter(o);
        mvc.perform(patch("/api/v2/links/"+id).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf()).contentType("application/json").content(json.writeValueAsString(clear)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.item.projectId").isEmpty()).andExpect(jsonPath("$.item.categoryId").isEmpty()).andExpect(jsonPath("$.item.projectName").isEmpty())
            .andExpect(jsonPath("$.item.revision").value(2)).andExpect(jsonPath("$.collectionRevision").value(4)).andExpect(header().string("X-Workspace-Data-Revision",Long.toString(before+1)));
        assertEquals(3,read(o,"/api/v2/links?category=uncategorized").path("total").asInt());
        mvc.perform(patch("/api/v2/links/"+id).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision",2,"projectId",p)))).andExpect(status().isOk()).andExpect(jsonPath("$.item.categoryId").value(b));
        mvc.perform(patch("/api/v2/links/"+id).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision",3,"label","Edited")))).andExpect(status().isOk()).andExpect(jsonPath("$.item.projectId").value(p));
    }
    @Test void strictRelationPresenceFingerprintsAndOwnership()throws Exception {
        var o=owner();var other=owner();String p=project(o,"Project",null),foreign=project(other,"Foreign",null);
        String omitted=json.writeValueAsString(Map.of("label","Link","url","https://example.com"));
        var body=new LinkedHashMap<String,Object>();body.put("label","Link");body.put("url","https://example.com");body.put("projectId",null);
        post(o,"/api/v2/links","key",omitted,201);post(o,"/api/v2/links","key",json.writeValueAsString(body),409);
        body.put("projectId",p);post(o,"/api/v2/links","raw",json.writeValueAsString(body),201);
        body.put("projectId",p.toUpperCase(Locale.ROOT));post(o,"/api/v2/links","raw",json.writeValueAsString(body),409);
        body.put("projectId",foreign);post(o,"/api/v2/links","foreign",json.writeValueAsString(body),404);
        for(Object invalid:List.of("",true,1,"uncategorized",List.of(),Map.of())){body.put("projectId",invalid);post(o,"/api/v2/links","invalid",json.writeValueAsString(body),400);}
        body.remove("projectId");body.put("categoryId",UUID.randomUUID());post(o,"/api/v2/links","direct-category",json.writeValueAsString(body),400);
        for(String bad:List.of("category=","category=null","category=all&category=all","scope=all","projectStatus=all&projectStatus=all","limit=1","cursor=x"))
            mvc.perform(get("/api/v2/links?"+bad).session(o.session())).andExpect(status().isBadRequest()).andExpect(header().doesNotExist("X-Workspace-Data-Revision"));
    }
}
