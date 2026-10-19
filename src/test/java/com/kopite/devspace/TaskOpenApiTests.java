package com.kopite.devspace;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class TaskOpenApiTests {
    private final MockMvc mvc;
    private final UserWorkspaceCreationService users;
    private final JsonMapper json;
    @Autowired
    TaskOpenApiTests(MockMvc mvc,UserWorkspaceCreationService users,JsonMapper json) {
        this.mvc=mvc; this.users=users; this.json=json;
    }
    @Test
    void generatedContractContainsAllTaskOperationsAndStrictSchemas() throws Exception {
        var user=users.createOrReuse("task-openapi",UUID.randomUUID().toString(),"OpenAPI");
        var session=new MockHttpSession();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(new InternalUserPrincipal(user.user().getId(),"OpenAPI"),null,List.of()));
        session.setAttribute("SPRING_SECURITY_CONTEXT",context);
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
        mvc.perform(get("/api/v2/tasks")).andExpect(status().isUnauthorized());
        String body=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var output=Path.of("build/reports/task-api/openapi.json"); Files.createDirectories(output.getParent()); Files.writeString(output,body);
        var api=json.readTree(body); var paths=api.get("paths");
        Map<String,Set<String>> methods=Map.of("/api/v2/tasks",Set.of("get","post"),"/api/v2/tasks/{id}",Set.of("get","patch","delete"),
            "/api/v2/tasks/{id}/restore",Set.of("post"),"/api/v2/tasks/stats",Set.of("get"));
        for(var entry:methods.entrySet()) {
            assertEquals(entry.getValue(),paths.get(entry.getKey()).properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
            for(String method:entry.getValue()) {
                var operation=paths.get(entry.getKey()).get(method);
                String success=entry.getKey().equals("/api/v2/tasks")&&method.equals("post")?"201":"200";
                assertTrue(operation.get("responses").has(success));
                assertTrue(operation.get("security").valueStream().anyMatch(s->s.has("sessionCookie")));
                for(String code:(method.equals("get") ? List.of("400","401","403","404","500") : List.of("400","401","403","404","409","500")))
                    assertEquals("#/components/schemas/ApiError",operation.get("responses").get(code).get("content").get("application/json").get("schema").get("$ref").asString());
                if(method.equals("get")) assertEquals(entry.getKey().equals("/api/v2/tasks"),operation.get("responses").has("409"));
                if(!method.equals("get")) assertTrue(parameter(operation,"X-CSRF-Token").get("required").asBoolean());
            }
        }
        var create=paths.get("/api/v2/tasks").get("post");
        assertFalse(create.get("responses").has("200"));
        assertTrue(parameter(create,"Idempotency-Key").get("required").asBoolean());
        assertEquals(128,parameter(create,"Idempotency-Key").get("schema").get("maxLength").asInt());
        var delete=paths.get("/api/v2/tasks/{id}").get("delete");
        assertTrue(parameter(delete,"revision").get("required").asBoolean());
        assertEquals("query",parameter(delete,"revision").get("in").asString());
        var list=paths.get("/api/v2/tasks").get("get");
        assertEquals("all",parameter(list,"projectStatus").get("schema").get("default").asString());
        assertEquals(20,parameter(list,"limit").get("schema").get("default").asInt());
        assertEquals(100,parameter(list,"limit").get("schema").get("maximum").asInt());
        assertFalse(parameter(list,"deleted").get("schema").get("default").asBoolean());
        var schemas=api.get("components").get("schemas");
        var request=schemas.get("CreateTaskRequest");
        assertEquals(Set.of("title","projectId"),strings(request.get("required")));
        assertEquals(Set.of("revision"),strings(schemas.get("UpdateTaskRequest").get("required")));
        assertEquals(Set.of("revision"),strings(schemas.get("RestoreTaskRequest").get("required")));
        for(String name:List.of("CreateTaskRequest","UpdateTaskRequest")) {
            var schema=schemas.get(name); assertFalse(schema.get("additionalProperties").asBoolean());
            var props=schema.get("properties");
            assertEquals(160,props.get("title").get("maxLength").asInt());
            assertEquals(10000,props.get("description").get("maxLength").asInt());
            assertEquals(40,props.get("tag").get("maxLength").asInt());
            assertEquals("string",props.get("tag").get("type").asString());
            assertEquals("uuid",props.get("projectId").get("format").asString());
            assertEquals(Set.of("normal","high"),strings(props.get("priority").get("enum")));
            assertEquals(Set.of("todo","doing","done"),strings(props.get("status").get("enum")));
            for(String forbidden:List.of("tags","workspaceId","projectName","categoryId","deletedAt","id")) assertFalse(props.has(forbidden));
        }
        assertEquals("",request.get("properties").get("tag").get("default").asString());
        assertEquals("",request.get("properties").get("description").get("default").asString());
        assertEquals("normal",request.get("properties").get("priority").get("default").asString());
        assertEquals("todo",request.get("properties").get("status").get("default").asString());
        var response=schemas.get("TaskResponse"); assertEquals(14,response.get("required").size());
        assertTrue(strings(response.get("properties").get("deletedAt").get("type")).contains("null"));
        for(String field:List.of("id","createdAt","updatedAt","projectName","categoryId","deletedAt"))
            assertTrue(response.get("properties").get(field).get("readOnly").asBoolean());
        assertEquals(Set.of("items","total","nextCursor","externalDataUnavailable"),strings(schemas.get("TaskListResponse").get("required")));
        assertEquals(Set.of("counts","total","asOf","externalDataUnavailable"),strings(schemas.get("TaskStatsResponse").get("required")));
    }
    private JsonNode parameter(JsonNode operation,String name) {
        return operation.get("parameters").valueStream().filter(p->p.get("name").asString().equals(name)).findFirst().orElseThrow();
    }
    private Set<String> strings(JsonNode values) { return values.valueStream().map(JsonNode::asString).collect(Collectors.toSet()); }
}
