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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ProjectOpenApiTests {
    private final MockMvc mvc;
    private final UserWorkspaceCreationService users;
    private final JsonMapper json;

    @Autowired
    ProjectOpenApiTests(MockMvc mvc, UserWorkspaceCreationService users, JsonMapper json) {
        this.mvc = mvc;
        this.users = users;
        this.json = json;
    }

    @Test
    void generatedOpenApiMatchesProjectOperationsAndSchemas() throws Exception {
        var user = users.createOrReuse("openapi-project", UUID.randomUUID().toString(), "OpenAPI Owner");
        var session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new InternalUserPrincipal(user.user().getId(), "OpenAPI Owner"), null, List.of()));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        String body = mvc.perform(get("/v3/api-docs").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Path output = Path.of("build", "reports", "project-api", "openapi.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, body);
        JsonNode api = json.readTree(body);
        var paths = api.get("paths");
        assertEquals(Set.of("/api/v2/projects", "/api/v2/projects/{id}", "/api/v2/projects/category-counts"), paths.properties().stream()
                .map(java.util.Map.Entry::getKey).filter(name -> name.startsWith("/api/v2/projects")&&!name.contains("/github/")).collect(Collectors.toSet()));
        assertEquals(Set.of("get", "post"), keys(paths.get("/api/v2/projects")));
        assertEquals(Set.of("get", "patch"), keys(paths.get("/api/v2/projects/{id}")));

        var create = paths.get("/api/v2/projects").get("post");
        var list = paths.get("/api/v2/projects").get("get");
        var get = paths.get("/api/v2/projects/{id}").get("get");
        var patch = paths.get("/api/v2/projects/{id}").get("patch");
        for (var operation : List.of(create, list, get, patch)) {
            assertTrue(operation.get("security").valueStream().anyMatch(value -> value.has("sessionCookie")));
            for (String code : List.of("400", "401", "403", "500")) {
                assertEquals("#/components/schemas/ApiError", operation.get("responses").get(code)
                        .get("content").get("application/json").get("schema").get("$ref").asString());
            }
        }
        assertEquals("#/components/schemas/ProjectResponse", responseSchema(create, "201"));
        assertFalse(create.get("responses").has("200"));
        assertEquals("#/components/schemas/ProjectResponse", responseSchema(get, "200"));
        assertEquals("#/components/schemas/ProjectResponse", responseSchema(patch, "200"));
        assertEquals("#/components/schemas/ProjectListResponse", responseSchema(list, "200"));
        assertTrue(create.get("responses").get("409").get("description").asString().contains("IDEMPOTENCY_KEY_REUSED"));
        assertTrue(patch.get("responses").get("409").get("description").asString().contains("REVISION_CONFLICT"));
        assertTrue(get.get("responses").has("404"));
        assertTrue(patch.get("responses").has("404"));
        assertEquals("uuid", parameter(get, "id").get("schema").get("format").asString());
        for (var operation : List.of(create, patch)) {
            assertTrue(parameter(operation, "X-CSRF-Token").get("required").asBoolean());
            assertEquals("header", parameter(operation, "X-CSRF-Token").get("in").asString());
            assertTrue(operation.get("requestBody").get("required").asBoolean());
        }
        var key = parameter(create, "Idempotency-Key");
        assertTrue(key.get("required").asBoolean());
        assertEquals(128, key.get("schema").get("maxLength").asInt());
        assertEquals("all", parameter(list, "category").get("schema").get("default").asString());
        assertEquals("active", parameter(list, "status").get("schema").get("default").asString());
        var limit = parameter(list, "limit").get("schema");
        assertEquals(20, limit.get("default").asInt());
        assertEquals(1, limit.get("minimum").asInt());
        assertEquals(100, limit.get("maximum").asInt());

        var schemas = api.get("components").get("schemas");
        var createSchema = schemas.get("CreateProjectRequest");
        var patchSchema = schemas.get("UpdateProjectRequest");
        assertEquals(Set.of("name", "stack"), strings(createSchema.get("required")));
        assertEquals(Set.of("revision"), strings(patchSchema.get("required")));
        assertFalse(createSchema.get("additionalProperties").asBoolean());
        assertFalse(patchSchema.get("additionalProperties").asBoolean());
        assertFalse(createSchema.get("properties").has("id"));
        assertFalse(createSchema.get("properties").has("status"));
        assertFalse(createSchema.get("properties").has("revision"));
        assertEquals(Set.of("active", "archived"), strings(patchSchema.get("properties").get("status").get("enum")));
        for (var schema : List.of(createSchema, patchSchema)) {
            var properties = schema.get("properties");
            assertEquals(100, properties.get("name").get("maxLength").asInt());
            assertEquals(200, properties.get("stack").get("maxLength").asInt());
            assertEquals(4000, properties.get("subtitle").get("maxLength").asInt());
            assertEquals(200, properties.get("currentMilestone").get("maxLength").asInt());
            assertEquals(2000, properties.get("repositoryUrl").get("maxLength").asInt());
            assertFalse(properties.has("scope"));
            assertEquals(0, properties.get("progress").get("minimum").asInt());
            assertEquals(100, properties.get("progress").get("maximum").asInt());
            assertTrue(hasType(properties.get("progress"), "number"));
            for (var property : properties.properties()) {
                if(property.getKey().equals("categoryId"))assertTrue(nullable(property.getValue()));
                else assertFalse(nullable(property.getValue()));
            }
        }
        for (String field : List.of("subtitle", "currentMilestone", "repositoryUrl")) {
            assertEquals("", createSchema.get("properties").get(field).get("default").asString());
        }
        assertEquals(0, createSchema.get("properties").get("progress").get("default").asInt());
        assertEquals(9007199254740991L, patchSchema.get("properties").get("revision").get("maximum").asLong());
        var response = schemas.get("ProjectResponse");
        assertEquals(12, response.get("required").size());
        assertTrue(nullable(response.get("properties").get("categoryId")));
        assertFalse(schemas.has("LegacyProjectResponse"));
        for (String field : List.of("id", "revision", "createdAt", "updatedAt")) {
            assertTrue(response.get("properties").get(field).get("readOnly").asBoolean());
        }
        assertEquals("date-time", response.get("properties").get("createdAt").get("format").asString());
        assertFalse(response.get("properties").has("workspaceId"));
        assertFalse(response.get("properties").has("dataRevision"));
        assertTrue(nullable(schemas.get("ProjectListResponse").get("properties").get("nextCursor")));
        assertEquals(Set.of("items", "total", "nextCursor"), strings(schemas.get("ProjectListResponse").get("required")));
        assertEquals(Set.of("code", "message", "fieldErrors", "requestId"), strings(schemas.get("ApiError").get("required")));
    }

    private String responseSchema(JsonNode operation, String status) {
        return operation.get("responses").get(status).get("content").get("application/json").get("schema").get("$ref").asString();
    }
    private JsonNode parameter(JsonNode operation, String name) {
        return operation.get("parameters").valueStream().filter(p -> p.get("name").asString().equals(name)).findFirst().orElseThrow();
    }
    private Set<String> keys(JsonNode object) { return object.properties().stream().map(java.util.Map.Entry::getKey).collect(Collectors.toSet()); }
    private Set<String> strings(JsonNode array) { return array.valueStream().map(JsonNode::asString).collect(Collectors.toSet()); }
    private boolean hasType(JsonNode schema, String type) {
        var value = schema.get("type");
        return value != null && (value.isArray() ? strings(value).contains(type) : value.asString().equals(type));
    }
    private boolean nullable(JsonNode schema) {
        return hasType(schema, "null") || (schema.has("nullable") && schema.get("nullable").asBoolean());
    }
}
