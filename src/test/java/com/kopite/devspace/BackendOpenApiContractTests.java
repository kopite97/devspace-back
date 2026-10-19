package com.kopite.devspace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
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
class BackendOpenApiContractTests {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private static final Map<String, Set<String>> INVENTORY = Map.ofEntries(
        Map.entry("/api/v1/auth/login", Set.of("get")),
        Map.entry("/api/v1/auth/callback/{registrationId}", Set.of("get")),
        Map.entry("/api/v1/auth/csrf", Set.of("get")),
        Map.entry("/api/v1/auth/logout", Set.of("post")),
        Map.entry("/api/v1/me", Set.of("get")),
        Map.entry("/api/v1/project-categories", Set.of("get", "post")),
        Map.entry("/api/v1/project-categories/{id}", Set.of("get", "patch", "delete")),
        Map.entry("/api/v2/projects", Set.of("get", "post")),
        Map.entry("/api/v2/projects/{id}", Set.of("get", "patch")),
        Map.entry("/api/v2/projects/category-counts", Set.of("get")),
        Map.entry("/api/v2/tasks", Set.of("get", "post")),
        Map.entry("/api/v2/tasks/{id}", Set.of("get", "patch", "delete")),
        Map.entry("/api/v2/tasks/{id}/restore", Set.of("post")),
        Map.entry("/api/v2/tasks/stats", Set.of("get")),
        Map.entry("/api/v2/journals", Set.of("get", "post")),
        Map.entry("/api/v2/journals/{id}", Set.of("get", "patch", "delete")),
        Map.entry("/api/v2/journals/{journalId}/documents", Set.of("get", "post")),
        Map.entry("/api/v2/documents/{documentId}", Set.of("get", "put", "delete")),
        Map.entry("/api/v2/documents/{documentId}/publish", Set.of("post")),
        Map.entry("/api/v2/attachments", Set.of("post")),
        Map.entry("/api/v2/attachments/{attachmentId}", Set.of("delete")),
        Map.entry("/api/v2/attachments/{attachmentId}/content", Set.of("get")),
        Map.entry("/api/v2/milestones", Set.of("get", "post")),
        Map.entry("/api/v2/milestones/{id}", Set.of("get", "patch", "delete")),
        Map.entry("/api/v2/links", Set.of("get", "post")),
        Map.entry("/api/v2/links/{id}", Set.of("get", "patch", "delete")),
        Map.entry("/api/v2/links/order", Set.of("put")),
        Map.entry("/api/v2/dashboards/home", Set.of("get", "put")),
        Map.entry("/api/v2/overview", Set.of("get")),
        Map.entry("/api/v1/widget-types", Set.of("get")),
        Map.entry("/api/v1/widgets", Set.of("get", "post")),
        Map.entry("/api/v1/widgets/{id}", Set.of("get", "put", "delete")),
        Map.entry("/api/v1/widgets/{id}/data", Set.of("get")),
        Map.entry("/api/v3/dashboards/home", Set.of("get", "put")),
        Map.entry("/api/v3/dashboards/home/initializations", Set.of("post")),
        Map.entry("/api/integrations/github/webhooks",Set.of("post")),
        Map.entry("/api/v2/integrations/github/account",Set.of("get","delete")),
        Map.entry("/api/v2/integrations/github/authorization-requests",Set.of("post")),
        Map.entry("/api/v2/integrations/github/callback",Set.of("get")),
        Map.entry("/api/v2/integrations/github/installations",Set.of("get")),
        Map.entry("/api/v2/integrations/github/installations/{installationId}/repositories",Set.of("get")),
        Map.entry("/api/v2/integrations/github/jobs/{jobId}",Set.of("get")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories",Set.of("get","post")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}",Set.of("get","delete")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/cleanup-requests",Set.of("post")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/import-settings",Set.of("get","put")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/status-mapping",Set.of("get","put")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/issue-fetches",Set.of("post")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/issues",Set.of("get")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/labels",Set.of("get")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/status-mapping-previews",Set.of("post")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/sync-requests",Set.of("post")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/sync-settings",Set.of("patch")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/sync-status",Set.of("get")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/task-import-previews",Set.of("post")),
        Map.entry("/api/v2/projects/{projectId}/github/repositories/{linkId}/task-imports",Set.of("post")),
        Map.entry("/api/v2/tasks/{taskId}/github-issue",Set.of("get","delete")));

    @Test void generatedInventoryExactlyMatchesApprovedSurfaceAndRuntimeMappings() throws Exception {
        var api = document();
        assertEquals(INVENTORY.keySet(), keys(api.path("paths")));
        Set<String> documented = new HashSet<>();
        Set<String> operationIds = new HashSet<>();
        for (var entry : INVENTORY.entrySet()) {
            assertEquals(entry.getValue(), keys(api.path("paths").path(entry.getKey())));
            for (String method : entry.getValue()) {
                var op = api.path("paths").path(entry.getKey()).path(method);
                documented.add(method + " " + entry.getKey());
                assertFalse(op.path("operationId").asString("").isBlank());
                assertTrue(operationIds.add(op.path("operationId").asString()), "Duplicate operationId");
                assertFalse(strings(op.path("tags")).isEmpty());
                assertFalse(strings(op.path("tags")).contains("default"));
                if (entry.getKey().contains("{id}")) {
                    var id = parameter(op, "id");
                    assertEquals("path", id.path("in").asString());
                    assertTrue(id.path("required").asBoolean());
                    assertEquals("uuid", id.path("schema").path("format").asString());
                }
            }
        }
        Set<String> implemented = new HashSet<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (handler.getBeanType().getName().startsWith("com.kopite.devspace.compatibility.")) return; // Historical replay/410 surface is independently HTTP-tested, not normal OpenAPI.
            for (String path : mapping.getPatternValues()) if (path.startsWith("/api/"))
                mapping.getMethodsCondition().getMethods().forEach(method -> implemented.add(method.name().toLowerCase(Locale.ROOT) + " " + path));
        });
        assertEquals(86, implemented.size());
        implemented.add("get /api/v1/auth/callback/{registrationId}");
        implemented.add("post /api/v1/auth/logout");
        assertEquals(88, documented.size());
        assertEquals(implemented, documented);
        assertReferencesResolve(api, api);
    }

    @Test void statusCodesErrorSchemasCsrfAndIdempotencyMatchEachOperation() throws Exception {
        var api = document();
        Set<String> creations = Set.of("/api/v2/projects", "/api/v1/project-categories", "/api/v2/tasks", "/api/v2/journals", "/api/v2/milestones", "/api/v2/links", "/api/v1/widgets", "/api/v3/dashboards/home/initializations");
        for (var entry : INVENTORY.entrySet()) for (String method : entry.getValue()) {
            String path = entry.getKey();
            var op = api.path("paths").path(path).path(method);
            if(path.contains("github")){assertGitHubOperation(path,method,op);continue;}
            String success = path.endsWith("/login") || path.contains("/callback/") ? "302"
                : path.endsWith("/logout") ? "204" : method.equals("post") && (creations.contains(path)||path.endsWith("/documents")||path.equals("/api/v2/attachments")) ? "201" : "200";
            assertEquals(path.equals("/api/v2/dashboards/home") ? Set.of() : Set.of(success), keys(op.path("responses")).stream().filter(c -> c.startsWith("2") || c.startsWith("3")).collect(Collectors.toSet()), path);
            for (var response : op.path("responses").properties()) if (Integer.parseInt(response.getKey()) >= 400) {
                assertEquals(Set.of("application/json"), keys(response.getValue().path("content")), path + " " + response.getKey());
                assertEquals("#/components/schemas/ApiError", response.getValue().path("content").path("application/json").path("schema").path("$ref").asString());
            }
            if (!method.equals("get")) {
                var csrf = parameter(op, "X-CSRF-Token");
                assertEquals("header", csrf.path("in").asString());
                assertEquals(!path.endsWith("/logout"), csrf.path("required").asBoolean());
            } else {
                assertEquals(path.equals("/api/v2/tasks"),op.path("responses").has("409"), path);
                assertFalse(hasParameter(op, "X-CSRF-Token"));
            }
            if (!path.equals("/api/v2/dashboards/home") && (path.startsWith("/api/v2/") || path.startsWith("/api/v1/project-categories") || path.startsWith("/api/v1/widgets") || path.startsWith("/api/v3/dashboards/"))) {
                var header = op.path("responses").path(success).path("headers").path("X-Workspace-Data-Revision");
                assertEquals("string", header.path("schema").path("type").asString(), path);
                assertEquals("^[0-9]+$", header.path("schema").path("pattern").asString());
                assertTrue(header.path("description").asString().contains("replays"));
            }
            boolean creation = method.equals("post") && creations.contains(path);
            assertEquals(creation, hasParameter(op, "Idempotency-Key"), path);
            if (creation) {
                var key = parameter(op, "Idempotency-Key");
                assertTrue(key.path("required").asBoolean());
                assertEquals(1, key.path("schema").path("minLength").asInt());
                assertEquals(128, key.path("schema").path("maxLength").asInt());
                assertTrue(op.path("responses").has("409"));
            }
            if (method.equals("delete") && !path.startsWith("/api/v2/documents/") && !path.startsWith("/api/v2/attachments/")) {
                var revision = parameter(op, "revision");
                assertTrue(revision.path("required").asBoolean());
                assertEquals("query", revision.path("in").asString());
                bounds(revision.path("schema"), 1);
                assertFalse(op.has("requestBody"));
            }
            if (op.path("responses").has("400")) {
                String description = op.path("responses").path("400").path("description").asString();
                assertEquals(method.equals("get") && ((creations.contains(path) && !path.endsWith("/links") && !path.endsWith("/project-categories") && !path.endsWith("/initializations")) || path.equals("/api/v1/widgets/{id}/data") || path.endsWith("/documents")), description.contains("INVALID_CURSOR"), path + " " + method);
                assertEquals(false, description.contains("UNSUPPORTED_SCHEMA_VERSION"), path + " " + method);
            }
        }
        assertTrue(api.path("paths").path("/api/v2/links").path("get").path("responses").has("404"));
        var errors = api.path("components").path("schemas").path("ApiError");
        assertEquals(Set.of("code", "message", "fieldErrors", "requestId"), strings(errors.path("required")));
    }

    @Test void responseRequiredFieldsAndOwnershipBoundariesAreConsistent() throws Exception {
        var schemas = document().path("components").path("schemas");
        for (var entry : schemas.properties()) {
            String name = entry.getKey();
            var schema = entry.getValue();
            if (!schema.has("properties")) continue;
            var fields = keys(schema.path("properties"));
            for (String forbidden : List.of("scope", "colorToken", "byScope", "workspaceId", "ownerUserId", "userId", "dataRevision", "owner", "password", "accessToken", "refreshToken"))
                assertFalse(fields.contains(forbidden), name + "." + forbidden);
            if (name.endsWith("Response") || name.startsWith("Overview") || name.equals("TaskStatusCounts")) {
                var required = new HashSet<>(fields);
                if (Set.of("DashboardWidgetResponse", "DashboardDataWidgetResponse").contains(name)) required.remove("limit");
                assertEquals(required, strings(schema.path("required")), name);
            }
            for (String field : List.of("revision", "collectionRevision")) if (name.endsWith("Response") && fields.contains(field))
                assertTrue(schema.path("properties").path(field).path("readOnly").asBoolean(), name + "." + field);
        }
        assertFalse(schemas.has("CsrfToken"));
        assertFalse(schemas.has("Counts"));
        for (String name : List.of("Task", "Project", "Journal", "Milestone", "Link")) {
            var response = schemas.path(name + "Response").path("properties");
            assertEquals("uuid", response.path("id").path("format").asString());
            bounds(response.path("revision"), 1);
            for (String date : List.of("createdAt", "updatedAt")) assertEquals("date-time", response.path(date).path("format").asString());
            assertEquals(Set.of("revision"), strings(schemas.path("Update" + name + "Request").path("required")));
            bounds(schemas.path("Update" + name + "Request").path("properties").path("revision"), 1);
            assertFalse(schemas.path("Update" + name + "Request").path("properties").path("revision").path("readOnly").asBoolean());
            for (String prefix : List.of("Create", "Update")) assertFalse(schemas.path(prefix + name + "Request").path("additionalProperties").asBoolean(true));
        }
        assertEquals("date", schemas.path("JournalResponse").path("properties").path("entryDate").path("format").asString());
        assertEquals(Set.of("string", "null"), types(schemas.path("MilestoneResponse").path("properties").path("dueDate")));
        assertEquals("date", schemas.path("MilestoneResponse").path("properties").path("dueDate").path("format").asString());
        assertEquals(Set.of("string", "null"), types(schemas.path("TaskResponse").path("properties").path("deletedAt")));
        assertEquals("date-time", schemas.path("TaskResponse").path("properties").path("deletedAt").path("format").asString());
    }

    @Test void paginationDefaultsEnumsAndWidgetRevisionSemanticsMatchPlans() throws Exception {
        var api = document(); var schemas = api.path("components").path("schemas");
        for (String feature : List.of("Project", "Task", "Journal", "Milestone")) {
            var list = api.path("paths").path("/api/v2/" + feature.toLowerCase(Locale.ROOT) + "s").path("get");
            var limit = parameter(list, "limit").path("schema");
            assertEquals(20, limit.path("default").asInt());
            assertEquals(1, limit.path("minimum").asInt()); assertEquals(100, limit.path("maximum").asInt());
            assertEquals(feature.equals("Task")?Set.of("items","total","nextCursor","externalDataUnavailable"):Set.of("items", "total", "nextCursor"), keys(schemas.path(feature + "ListResponse").path("properties")));
            assertEquals(Set.of("string", "null"), types(schemas.path(feature + "ListResponse").path("properties").path("nextCursor")));
        }
        assertEquals(Set.of("null"), types(schemas.path("LinkListResponse").path("properties").path("nextCursor")));
        assertEquals(Set.of("todo", "doing", "done"), strings(schemas.path("CreateTaskRequest").path("properties").path("status").path("enum")));
        assertEquals(Set.of("normal", "high"), strings(schemas.path("CreateTaskRequest").path("properties").path("priority").path("enum")));
        assertEquals("todo", schemas.path("CreateTaskRequest").path("properties").path("status").path("default").asString());
        for (String feature : List.of("Task", "Link")) assertEquals("", schemas.path("Create" + feature + "Request").path("properties").path("description").path("default").asString());
        for (String schema : List.of("HomeLayoutSnapshot", "SaveHomeLayoutRequest")) {
            bounds(schemas.path(schema).path("properties").path("layoutRevision"), 0);
        }
        bounds(schemas.path("ReorderLinksRequest").path("properties").path("collectionRevision"), 0);
        assertEquals(6, schemas.path("CreateWidgetRequest").path("oneOf").size());
        assertEquals(4, schemas.path("WidgetSelection").path("oneOf").size());
        assertEquals(Set.of("valid", "missingCategory"), strings(schemas.path("WidgetSnapshot").path("properties").path("referenceState").path("enum")));
        assertFalse(schemas.path("LocalWidgetConfig").path("properties").path("limit").has("default"));
        assertFalse(schemas.path("WidgetConfig_links").path("properties").has("limit"));
        assertFalse(schemas.path("WidgetConfig_deploy").path("properties").has("limit"));
        assertEquals(Set.of("string", "null"), types(schemas.path("OverviewResponse").path("properties").path("projectId")));
    }

    private void assertGitHubOperation(String path,String method,JsonNode operation){
        boolean webhook=path.equals("/api/integrations/github/webhooks");String success="200";
        if(method.equals("post")&&(path.endsWith("/authorization-requests")||path.endsWith("/repositories")))success="201";
        if(method.equals("post")&&(webhook||path.endsWith("/sync-requests")||path.endsWith("/issue-fetches")||path.endsWith("/task-imports")||path.endsWith("/cleanup-requests")))success="202";
        if(method.equals("delete"))success=path.endsWith("/account")||path.endsWith("/github-issue")?"204":"202";
        if(path.endsWith("/callback"))success="303";
        assertEquals(Set.of(success),keys(operation.path("responses")).stream().filter(code->code.startsWith("2")||code.startsWith("3")).collect(Collectors.toSet()),path);
        for(var response:operation.path("responses").properties())if(Integer.parseInt(response.getKey())>=400)assertEquals("#/components/schemas/ApiError",response.getValue().path("content").path("application/json").path("schema").path("$ref").asString(),path);
        assertFalse(operation.path("responses").path(success).path("headers").has("X-Workspace-Data-Revision"));
        if(!method.equals("get")&&!webhook)assertTrue(parameter(operation,"X-CSRF-Token").path("required").asBoolean());
        boolean idempotent=method.equals("post")&&(path.endsWith("/repositories")||path.endsWith("/sync-requests")||path.endsWith("/issue-fetches")||path.endsWith("/task-imports"));
        assertEquals(idempotent,hasParameter(operation,"Idempotency-Key"));
        if(webhook)assertTrue(operation.path("security").isEmpty());
        else assertTrue(operation.path("security").valueStream().anyMatch(s->s.has("sessionCookie")));
    }
    private JsonNode document() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Path output = Path.of("build/reports/backend-openapi-review/openapi.json");
        Files.createDirectories(output.getParent()); Files.writeString(output, body);
        return json.readTree(body);
    }
    private void assertReferencesResolve(JsonNode node, JsonNode root) {
        if (node.isObject()) {
            if (node.has("$ref")) {
                String ref = node.path("$ref").asString();
                assertTrue(ref.startsWith("#/"));
                assertFalse(root.at(ref.substring(1)).isMissingNode(), ref);
            }
            node.properties().forEach(entry -> assertReferencesResolve(entry.getValue(), root));
        } else if (node.isArray()) node.forEach(value -> assertReferencesResolve(value, root));
    }
    private boolean hasParameter(JsonNode op, String name) { return op.path("parameters").valueStream().anyMatch(p -> p.path("name").asString().equals(name)); }
    private JsonNode parameter(JsonNode op, String name) { return op.path("parameters").valueStream().filter(p -> p.path("name").asString().equals(name)).findFirst().orElseThrow(); }
    private Set<String> keys(JsonNode node) { return node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()); }
    private Set<String> strings(JsonNode node) { return node.valueStream().map(JsonNode::asString).collect(Collectors.toSet()); }
    private Set<String> types(JsonNode schema) { var type = schema.path("type"); return type.isArray() ? strings(type) : Set.of(type.asString()); }
    private void bounds(JsonNode schema, long minimum) { assertEquals(minimum, schema.path("minimum").asLong()); assertEquals(9007199254740991L, schema.path("maximum").asLong()); }
}
