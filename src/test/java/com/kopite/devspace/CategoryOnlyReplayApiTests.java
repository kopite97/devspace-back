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

// An obsolete true property must never re-enable historical HTTP replay.
@SpringBootTest(properties="app.category-transition.legacy-replay-enabled=true") @ActiveProfiles("test") @Import(TestcontainersConfiguration.class) @AutoConfigureMockMvc
class CategoryOnlyReplayApiTests {
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
    @Test void allHistoricalShapesAndCrossVersionKeysRemainImmutable()throws Exception {
        var o=owner();UUID project=UUID.fromString("10000000-0000-0000-0000-000000000001"),task=UUID.fromString("30000000-0000-0000-0000-000000000001");
        jdbc.update("insert into projects(id,workspace_id,name,stack,created_at,updated_at) values(?,?,'Project','Java',now(),now())",project,o.workspace());
        jdbc.update("insert into tasks(id,workspace_id,project_id,title,created_at,updated_at,deleted_at) values(?,?,?,'Task',now(),now(),now())",task,o.workspace(),project);
        int n=0;var evidence=new ArrayList<String>();
        for(var fixture:json.readTree(Files.readString(Path.of("src/test/resources/category-only/legacy-creations.json")))) {
            String r=fixture.path("resource").asString(),key="historical-"+n++,body=fixture.path("requestBody").asString(),expected=fixture.path("responseBody").asString();
            jdbc.update("insert into "+r+"_create_idempotency values(?,'POST',?,?,?,201,?,now(),now()+interval '24 hours')",o.workspace(),"/api/v1/"+r+"s",key,fixture.path("requestHash").asString(),expected);
            var before=jdbc.queryForMap("select * from "+r+"_create_idempotency where workspace_id=? and key=?",o.workspace(),key);
            assertEquals("API_VERSION_RETIRED",json.readTree(post(o,"/api/v1/"+r+"s",key,body,410)).path("code").asString());
            var normal=(tools.jackson.databind.node.ObjectNode)json.readTree(body);normal.remove("scope");
            assertEquals("IDEMPOTENCY_KEY_REUSED",json.readTree(post(o,"/api/v2/"+r+"s",key,normal.toString(),409)).path("code").asString());
            assertEquals(before,jdbc.queryForMap("select * from "+r+"_create_idempotency where workspace_id=? and key=?",o.workspace(),key));
            evidence.add(r+": retired HTTP; cross-version key conflict; historical row byte-for-byte immutable");
        }
        assertEquals(0L,jdbc.queryForObject("select data_revision from workspaces where id=?",Long.class,o.workspace()));
        assertEquals("API_VERSION_RETIRED",json.readTree(post(o,"/api/v1/projects","missing","{\"name\":\"Project\",\"scope\":\"unity\",\"stack\":\"Java\"}",410)).path("code").asString());
        mvc.perform(get("/api/v1/projects").session(o.session())).andExpect(status().isGone());
        post(o,"/api/v1/projects","invalid","{",410);
        post(o,"/api/v1/projects","invalid","{\"name\":\"x\",\"name\":\"y\",\"scope\":\"unity\",\"stack\":\"Java\"}",410);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/projects").session(o.session()).header("Origin",origin)
            .header("Idempotency-Key","unauthorized").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        Path out=Path.of(".gradle/project-category-only-validation/replay");Files.createDirectories(out);Files.write(out.resolve("all-resources.txt"),evidence);
    }
    @Test void newProjectSnapshotAndPresenceReplayDoNotResolveCurrentCategory()throws Exception {
        var o=owner();String c=post(o,"/api/v1/project-categories","c","{\"name\":\"Category\"}",201);
        String id=json.readTree(c).path("id").asString();
        String body="{\"name\":\"Project\",\"stack\":\"Java\",\"categoryId\":\""+id+"\"}";
        String created=post(o,"/api/v2/projects","create",body,201);var p=json.readTree(created);
        assertFalse(p.has("scope"));assertFalse(p.has("colorToken"));assertEquals(12,p.size());
        mvc.perform(patch("/api/v2/projects/"+p.path("id").asString()).session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf())
            .contentType("application/json").content("{\"revision\":1,\"status\":\"archived\",\"categoryId\":null}")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/project-categories/"+id).queryParam("revision","1").session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf())).andExpect(status().isOk());
        assertEquals(created,post(o,"/api/v2/projects","create",body,201));
        String omitted="{\"name\":\"Other\",\"stack\":\"Java\"}";String result=post(o,"/api/v2/projects","omitted",omitted,201);
        assertTrue(json.readTree(result).path("categoryId").isNull());
        post(o,"/api/v2/projects","omitted","{\"name\":\"Other\",\"stack\":\"Java\",\"categoryId\":null}",409);
        post(o,"/api/v1/projects","omitted","{\"name\":\"Other\",\"stack\":\"Java\",\"scope\":\"unity\"}",410);
        post(o,"/api/v2/projects","bad","{\"name\":\"Other\",\"stack\":\"Java\",\"scope\":\"unity\"}",400);
    }
}
