package com.kopite.devspace;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.project.application.command.*;
import com.kopite.devspace.project.infrastructure.persistence.ProjectCreateReplayAdapter;
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
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Retained historical records remain readable while the old HTTP endpoint is permanently retired.
@SpringBootTest(properties="app.category-transition.legacy-replay-enabled=true") @ActiveProfiles("test") @Import(TestcontainersConfiguration.class) @AutoConfigureMockMvc
class ProjectLegacyReplayTests {
    @Autowired UserWorkspaceCreationService users;
    @Autowired ProjectCommandService commands;
    @Autowired ProjectCreateReplayAdapter replays;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired tools.jackson.databind.json.JsonMapper json;
    @org.springframework.beans.factory.annotation.Value("${app.security.origin}") String origin;

    @Test void migratedOldSnapshotIsPreservedBehindRetiredHttpAndNewSnapshotIncludesNull() throws Exception {
        var owner=users.createOrReuse("legacy-replay",UUID.randomUUID().toString(),"Owner");
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(new InternalUserPrincipal(owner.user().getId(),"Owner"),null,List.of()));
        var session=new MockHttpSession(); session.setAttribute("SPRING_SECURITY_CONTEXT",context);
        var csrf=json.readTree(mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn().getResponse().getContentAsString());
        String token=csrf.get("csrfToken").asString();
        String body=Files.readString(Path.of("src/test/resources/project-category/legacy-project-response.json")).strip();
        String hash=Files.readString(Path.of("src/test/resources/project-category/legacy-project-hash.txt")).strip();
        String schema="http_replay_upgrade_"+UUID.randomUUID().toString().replace("-","");
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("12").load().migrate();
        jdbc.update("insert into "+schema+".users values(?,'Owner',now(),now(),null)",owner.user().getId());
        jdbc.update("insert into "+schema+".workspaces values(?,?,'Workspace',7,42,now(),now())",owner.workspace().getId(),owner.user().getId());
        jdbc.update("insert into "+schema+".project_create_idempotency values(?,'POST','/api/v1/projects','old',?,201,?,now(),now()+interval '24 hours')",owner.workspace().getId(),hash,body);
        var before=jdbc.queryForMap("select * from "+schema+".project_create_idempotency");
        String manifest=json.writeValueAsString(Map.of("workspaces",List.of(Map.of("workspaceId",owner.workspace().getId().toString(),"expectedDataRevision",42,"links",List.of()))));
        var upgraded=org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("16")
            .initSql("select set_config('devspace.category_cutover_manifest','"+manifest+"',false)").load();upgraded.migrate();upgraded.validate();
        assertEquals(before,jdbc.queryForMap("select * from "+schema+".project_create_idempotency"));
        // Move the exact migrated test fixture into the application's disposable test schema for HTTP replay.
        jdbc.update("insert into project_create_idempotency select * from "+schema+".project_create_idempotency");
        var expiry=jdbc.queryForObject("select expires_at from project_create_idempotency where workspace_id=?",java.sql.Timestamp.class,owner.workspace().getId());
        // No live Project row exists: replay must neither look it up nor resurrect it.
        String actual=mvc.perform(post("/api/v1/projects").session(session).header("X-CSRF-Token",token).header("Origin",origin)
            .header("Idempotency-Key","old").contentType("application/json").content("{\"name\":\"Project\",\"scope\":\"unity\",\"stack\":\"Java\"}"))
            .andExpect(handler().methodName("replay")).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("API_VERSION_RETIRED")).andReturn().getResponse().getContentAsString();
        assertEquals(body,jdbc.queryForObject("select response_body from project_create_idempotency where workspace_id=? and key='old'",String.class,owner.workspace().getId()));
        assertTrue(replays.find(owner.workspace().getId(),"old").orElseThrow().legacy());
        assertEquals(0L,jdbc.queryForObject("select data_revision from workspaces where id=?",Long.class,owner.workspace().getId()));
        assertEquals(expiry,jdbc.queryForObject("select expires_at from project_create_idempotency where workspace_id=?",java.sql.Timestamp.class,owner.workspace().getId()));
        var created=commands.create(owner.user().getId(),"new",new CreateProjectCommand("Project",null,"Java",null,null,null));
        String stored=jdbc.queryForObject("select response_body from project_create_idempotency where workspace_id=? and key='new'",String.class,owner.workspace().getId());
        assertTrue(json.readTree(stored).get("categoryId").isNull()); assertFalse(json.readTree(stored).has("legacyResponse"));
        SnapshotAssertions.assertDataEquals(created,commands.create(owner.user().getId(),"new",new CreateProjectCommand("Project",null,"Java",null,null,null)));
        jdbc.update("update project_create_idempotency set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where workspace_id=? and key='old'",owner.workspace().getId());
        assertTrue(replays.find(owner.workspace().getId(),"old").orElseThrow().legacy());
        assertEquals(body,jdbc.queryForObject("select response_body from project_create_idempotency where workspace_id=? and key='old'",String.class,owner.workspace().getId()));
        Path out=Path.of(".gradle/plan0014/replay");Files.createDirectories(out);Files.writeString(out.resolve("retired-response.json"),actual);Files.writeString(out.resolve("new-omitted-response.json"),stored);
    }
}
