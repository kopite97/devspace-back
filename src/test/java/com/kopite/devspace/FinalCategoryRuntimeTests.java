package com.kopite.devspace;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Separate final DB: even the obsolete true override cannot restore legacy HTTP serving. */
@SpringBootTest(properties={"app.category-transition.legacy-replay-enabled=true",
    "spring.flyway.locations=filesystem:src/main/resources/db/migration,filesystem:src/main/migration-stages/cutover,filesystem:src/main/migration-stages/contract"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Testcontainers
class FinalCategoryRuntimeTests {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        try {
            var root=java.nio.file.Files.createTempDirectory("devspace-final-storage-");
            java.nio.file.Files.writeString(root.resolve(".devspace-document-storage"),"devspace-document-storage-v1");
            r.add("app.storage.root",root::toString);
            r.add("app.attachment.cleanup.enabled",()->false);
        }catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired JsonMapper json;
    @Autowired UserWorkspaceCreationService users; @Value("${app.security.origin}") String origin;

    @Test void finalSchemaSupportsNormalRelationsAndRetiresLegacyWithoutWriting()throws Exception {
        assertEquals("39",jdbc.queryForObject("select version from flyway_schema_history order by installed_rank desc limit 1",String.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='public' and column_name='scope'",Integer.class));
        var owner=users.createOrReuse("final",UUID.randomUUID().toString(),"Final");
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(new InternalUserPrincipal(owner.user().getId(),"Final"),null,List.of()));
        var session=new MockHttpSession();session.setAttribute("SPRING_SECURITY_CONTEXT",context);
        String csrf=json.readTree(mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).path("csrfToken").asString();
        var category=json.readTree(create(session,csrf,"/api/v1/project-categories","category","{\"name\":\"User category\"}"));
        var project=json.readTree(create(session,csrf,"/api/v2/projects","project","{\"name\":\"Project\",\"stack\":\"Java\",\"categoryId\":\""+category.path("id").asString()+"\"}"));
        String id=project.path("id").asString();
        assertFalse(project.has("scope"));assertFalse(project.has("colorToken"));
        create(session,csrf,"/api/v2/tasks","task","{\"title\":\"Task\",\"projectId\":\""+id+"\"}");
        create(session,csrf,"/api/v2/journals","journal","{\"title\":\"Journal\",\"body\":\"Body\",\"entryDate\":\"2026-09-15\",\"projectId\":\""+id+"\"}");
        create(session,csrf,"/api/v2/milestones","milestone","{\"title\":\"Milestone\",\"projectId\":\""+id+"\"}");
        create(session,csrf,"/api/v2/links","link","{\"label\":\"Link\",\"url\":\"https://example.com\",\"projectId\":\""+id+"\"}");
        for(String path:List.of("projects","projects/category-counts","tasks","tasks/stats","journals","milestones","links","overview"))
            mvc.perform(get("/api/v2/"+path).session(session)).andExpect(status().isOk()).andExpect(header().string("X-Workspace-Data-Revision","6"));
        mvc.perform(get("/api/v3/dashboards/home").session(session)).andExpect(status().isOk()).andExpect(header().string("X-Workspace-Data-Revision","6"));
        mvc.perform(get("/api/v2/dashboards/home").session(session)).andExpect(status().isGone());
        mvc.perform(delete("/api/v1/project-categories/"+category.path("id").asString()).session(session).queryParam("revision","1").header("Origin",origin).header("X-CSRF-Token",csrf))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
        for(String path:List.of("projects","tasks","journals","milestones","links"))
            mvc.perform(post("/api/v1/"+path).session(session).header("Origin",origin).header("X-CSRF-Token",csrf).header("Idempotency-Key","old").contentType("application/json").content("{}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("API_VERSION_RETIRED")).andExpect(header().doesNotExist("X-Workspace-Data-Revision"));
        assertEquals(6L,jdbc.queryForObject("select data_revision from workspaces where id=?",Long.class,owner.workspace().getId()));
        for(String table:List.of("project","task","journal","milestone","link"))
            assertEquals(0,jdbc.queryForObject("select count(*) from "+table+"_create_idempotency where key='old'",Integer.class));
        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/widgets").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/widget-types").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/projects").session(session).header("Origin",origin)
            .header("Idempotency-Key","old").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/projects").session(session).header("Origin",origin).header("X-CSRF-Token",csrf)
            .contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/projects").session(session).header("Origin",origin).header("X-CSRF-Token",csrf)
            .header("Idempotency-Key","old").contentType("application/json"))
            .andExpect(status().isBadRequest());
        jdbc.update("update users set disabled_at=now() where id=?",owner.user().getId());
        mvc.perform(get("/api/v1/projects").session(session)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }
    String create(MockHttpSession session,String csrf,String path,String key,String body)throws Exception {
        return mvc.perform(post(path).session(session).header("Origin",origin).header("X-CSRF-Token",csrf).header("Idempotency-Key",key).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
    }
}
