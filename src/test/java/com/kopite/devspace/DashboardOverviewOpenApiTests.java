package com.kopite.devspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class DashboardOverviewOpenApiTests {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Test void overviewAndReplacementLayoutDtosAndExamples() throws Exception {
        String body=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Path out=Path.of("build/reports/dashboard-overview-api/openapi.json");Files.createDirectories(out.getParent());Files.writeString(out,body);
        var api=json.readTree(body);var paths=api.get("paths");var schemas=api.get("components").get("schemas");
        assertEquals(Set.of("get"),keys(paths.get("/api/v2/overview")));
        assertEquals(Set.of("get","put"),keys(paths.get("/api/v3/dashboards/home")));
        for(var entry:Map.of("/api/v2/overview",List.of("get"),"/api/v3/dashboards/home",List.of("get","put")).entrySet())
            for(String method:entry.getValue()) {
                var op=paths.get(entry.getKey()).get(method);assertTrue(op.get("responses").has("200"));assertFalse(op.get("responses").has("201"));
                assertTrue(op.get("security").valueStream().anyMatch(s->s.has("sessionCookie")));
                for(String code:List.of("400","401","403","404","500"))assertEquals("#/components/schemas/ApiError",op.get("responses").get(code).get("content").get("application/json").get("schema").get("$ref").asString());
                if(op.has("parameters"))assertFalse(op.get("parameters").valueStream().anyMatch(p->p.get("name").asString().equals("Idempotency-Key")));
            }
        var save=paths.get("/api/v3/dashboards/home").get("put");assertTrue(save.get("responses").has("409"));
        assertEquals(Set.of("X-CSRF-Token"),save.get("parameters").valueStream().map(p->p.get("name").asString()).collect(Collectors.toSet()));
        assertTrue(save.get("parameters").get(0).get("required").asBoolean());
        assertEquals("header",save.get("parameters").get(0).get("in").asString());
        assertTrue(save.get("responses").get("400").get("description").asString().contains("VALIDATION_ERROR"));
        var overview=paths.get("/api/v2/overview").get("get");
        assertEquals(Set.of("category","projectId"),overview.get("parameters").valueStream().map(p->p.get("name").asString()).collect(Collectors.toSet()));
        var scope=overview.get("parameters").valueStream().filter(p->p.get("name").asString().equals("category")).findFirst().orElseThrow();
        assertEquals("all",scope.get("schema").get("default").asString());assertFalse(scope.get("schema").has("enum"));
        Map<String,Set<String>> fields=Map.of(
            "SaveHomeLayoutRequest",Set.of("schemaVersion","layoutRevision","placements"),
            "HomeLayoutSnapshot",Set.of("id","schemaVersion","initialized","layoutRevision","placements","widgets"),
            "OverviewResponse",Set.of("category","projectId","projects","tasks","asOf","externalDataUnavailable"),
            "OverviewProjectCounts",Set.of("total","archived","byCategory"),
            "OverviewProjectCategoryCounts",Set.of("categoryId","total","archived"),
            "OverviewTaskCounts",Set.of("todo","doing","done","total"));
        for(var entry:fields.entrySet()) {var s=schemas.get(entry.getKey());assertEquals(entry.getValue(),keys(s.get("properties")));assertEquals(entry.getValue(),strings(s.get("required")));assertFalse(s.get("additionalProperties").asBoolean());}
        for(String name:List.of("SaveHomeLayoutRequest","HomeLayoutSnapshot")) {
            var props=schemas.get(name).get("properties");assertEquals(0,props.get("layoutRevision").get("minimum").asLong());assertEquals(9007199254740991L,props.get("layoutRevision").get("maximum").asLong());
        }
        assertEquals(6,schemas.get("CreateWidgetRequest").get("oneOf").size());
        assertEquals(4,schemas.get("WidgetSelection").get("oneOf").size());
        assertTrue(paths.get("/api/v2/dashboards/home").get("put").get("responses").has("410"));
        var projectId=schemas.get("OverviewResponse").get("properties").get("projectId");
        assertTrue(strings(projectId.get("type")).contains("null"));assertEquals("date-time",schemas.get("OverviewResponse").get("properties").get("asOf").get("format").asString());
        var examples=paths.get("/api/v3/dashboards/home").get("get").get("responses").get("200").get("content").get("application/json").get("examples");
        assertEquals(0,examples.get("unsaved").get("value").get("layoutRevision").asLong());assertEquals(0,examples.get("unsaved").get("value").get("widgets").size());
        assertFalse(examples.get("unsaved").get("value").get("initialized").asBoolean());
        var workspaceExample=overview.get("responses").get("200").get("content").get("application/json").get("examples").get("workspace").get("value");
        assertTrue(workspaceExample.get("projectId").isNull());
    }
    Set<String> keys(JsonNode n){return n.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());}
    Set<String> strings(JsonNode n){if(n.isString())return Set.of(n.asString());return n.valueStream().map(JsonNode::asString).collect(Collectors.toSet());}
}
