package com.kopite.devspace;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class DocumentOpenApiTests extends DocumentTestSupport {
    @Test void exactlyNineOperationsDescribeOpaqueJsonMultipartAndBinary()throws Exception {
        var response=mvc.perform(get("/v3/api-docs")).andReturn().getResponse();assertEquals(200,response.getStatus());var api=tree(response);
        var expected=Map.of("/api/v2/journals/{journalId}/documents",Set.of("get","post"),"/api/v2/documents/{documentId}",Set.of("get","put","delete"),"/api/v2/documents/{documentId}/publish",Set.of("post"),"/api/v2/attachments",Set.of("post"),"/api/v2/attachments/{attachmentId}",Set.of("delete"),"/api/v2/attachments/{attachmentId}/content",Set.of("get"));
        int count=0;
        for(var entry:expected.entrySet()) {
            var path=api.path("paths").path(entry.getKey());assertEquals(entry.getValue(),path.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
            for(String method:entry.getValue()) {
                count++;var op=path.path(method);assertTrue(op.path("responses").has("401"));assertTrue(op.path("responses").has("404"));
                if(method.equals("get"))assertFalse(op.path("responses").has("409"));
                else assertTrue(op.path("parameters").valueStream().anyMatch(p->p.path("name").asString().equals("X-CSRF-Token")&&p.path("required").asBoolean()));
                if(method.equals("delete")||entry.getKey().endsWith("/publish"))assertFalse(op.has("requestBody"));
            }
        }
        assertEquals(9,count);var schemas=api.path("components").path("schemas");
        for(String schema:List.of("DocumentSnapshot","DocumentSummary")){var model=schemas.path(schema);assertTrue(model.path("required").valueStream().anyMatch(n->n.asString().equals("publishedAt")));assertTrue(model.path("properties").path("publishedAt").path("type").valueStream().anyMatch(n->n.asString().equals("null")));}
        assertEquals(100,schemas.path("UpdateDocumentRequest").path("properties").path("attachmentIds").path("maxItems").asInt());
        assertEquals(100,schemas.path("DocumentSnapshot").path("properties").path("attachments").path("maxItems").asInt());
        assertTrue(schemas.path("DocumentSnapshot").path("required").valueStream().anyMatch(n->n.asString().equals("attachments")));
        assertEquals(8,schemas.path("AttachedSnapshot").path("required").size());
        assertEquals("ATTACHED",schemas.path("AttachedSnapshot").path("properties").path("status").path("enum").get(0).asString());
        assertEquals("TEMP",schemas.path("AttachmentSnapshot").path("properties").path("status").path("enum").get(0).asString());
        for(String name:List.of("DeletedDocument","DeletedAttachment")) {
            assertTrue(schemas.path(name).path("required").valueStream().anyMatch(n->n.asString().equals("deletedId")));
            assertEquals("uuid",schemas.path(name).path("properties").path("deletedId").path("format").asString());
            assertFalse(schemas.path(name).path("properties").has("deletedIt"));
        }
        var upload=api.path("paths").path("/api/v2/attachments").path("post").path("requestBody").path("content").path("multipart/form-data").path("schema");
        assertEquals("uuid",upload.path("properties").path("documentId").path("format").asString());assertEquals("binary",upload.path("properties").path("file").path("format").asString());
        var download=api.path("paths").path("/api/v2/attachments/{attachmentId}/content").path("get").path("responses").path("200");assertEquals("binary",download.path("content").path("application/pdf").path("schema").path("format").asString());assertTrue(download.path("headers").has("Content-Disposition"));
    }
}
