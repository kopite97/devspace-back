package com.kopite.devspace.document.presentation;
import org.springframework.context.annotation.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.headers.Header;
import java.util.*;
import java.math.BigDecimal;
@Configuration(proxyBeanMethods=false)
public class DocumentOpenApiConfiguration {
    @Bean OpenApiCustomizer documentContracts(){return api->{
        var schemas=api.getComponents().getSchemas();
        for(String name:List.of("CreateDocumentRequest","UpdateDocumentRequest","DocumentSnapshot","DocumentPage","DocumentSummary","AttachmentSnapshot","AttachedSnapshot","DeletedDocument","DeletedAttachment")) {
            Schema<?> s=schemas.get(name);if(s==null||s.getProperties()==null)continue;
            s.setRequired(List.copyOf(s.getProperties().keySet()));s.setAdditionalProperties(false);
            if(s.getProperties().containsKey("content"))s.addProperty("content",new ArraySchema().items(new Schema<>()).description("Opaque BlockNote JSON array; no backend block schema. Empty array allowed. Parsed UTF-8 serialization defaults to 1048576 bytes; complete JSON request defaults to 1114112 bytes (content limit + 65536). DOCUMENT_CONTENT_MAX_BYTES configures the content limit. Depth limit 100 (400); byte overflow 413."));
            if(s.getProperties().containsKey("publishedAt"))s.getProperties().get("publishedAt").setTypes(Set.of("string","null"));
            if(s.getProperties().containsKey("nextCursor"))s.getProperties().get("nextCursor").setTypes(Set.of("string","null"));
        }
        schemas.get("DocumentSnapshot").addProperty("attachments",new ArraySchema().items(new Schema<>().$ref("#/components/schemas/AttachedSnapshot")).maxItems(100).description("All saved ATTACHED metadata, same snapshot and UUID order as attachmentIds. TEMP excluded. Never replace a failed restore with an empty registry."));
        ((Schema<?>)schemas.get("DocumentSnapshot").getProperties().get("attachmentIds")).setMaxItems(100);
        schemas.get("AttachedSnapshot").addProperty("status",new StringSchema()._enum(List.of("ATTACHED")));
        schemas.get("AttachmentSnapshot").addProperty("status",new StringSchema()._enum(List.of("TEMP")));
        api.getPaths().forEach((path,item)->{
            if(!path.startsWith("/api/v2/documents/")&&!path.matches("/api/v2/journals/[^/]+/documents")&&!path.startsWith("/api/v2/attachments"))return;
            item.readOperationsMap().forEach((method,op)->{
                boolean mutation=!method.name().equals("GET");
                boolean content=path.endsWith("/content");
                if(content){
                    var binary=new Content();for(var type:List.of("image/png","image/jpeg","application/pdf","text/plain"))binary.addMediaType(type,new MediaType().schema(new StringSchema().format("binary")));
                    op.getResponses().get("200").setContent(binary);
                    for(var header:List.of("Content-Length","Content-Type","Content-Disposition","X-Content-Type-Options","X-Request-ID"))op.getResponses().get("200").addHeaderObject(header,new Header().schema(new StringSchema()));
                }
                if(path.equals("/api/v2/attachments")&&mutation) {
                    var multipart=new ObjectSchema().addProperty("documentId",new StringSchema().format("uuid")).addProperty("file",new StringSchema().format("binary"));
                    multipart.setDescription("One PNG/JPEG/PDF/UTF-8 TXT file; non-empty. Default file limit 10485760 bytes (ATTACHMENT_MAX_BYTES); whole Multipart limit 11534336 bytes (ATTACHMENT_REQUEST_MAX_BYTES). File format errors: 400 VALIDATION_ERROR; byte overflow: 413 PAYLOAD_TOO_LARGE.");
                    multipart.setRequired(List.of("documentId","file"));multipart.setAdditionalProperties(false);
                    op.setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody().required(true).content(new Content().addMediaType("multipart/form-data",new MediaType().schema(multipart))));
                    if(op.getParameters()!=null)op.getParameters().removeIf(p->p.getName().equals("documentId"));
                }
                if(method.name().equals("DELETE")||path.endsWith("/publish"))op.setRequestBody(null);
                if(mutation)op.addParametersItem(new Parameter().in("header").name("X-CSRF-Token").required(true).schema(new StringSchema()));
                for(String code:List.of("400","401","403","404","500"))op.getResponses().putIfAbsent(code,new ApiResponse().description("Common Document contract error"));
                if(!mutation&&path.endsWith("/documents"))op.getResponses().get("400").setDescription("VALIDATION_ERROR or INVALID_CURSOR");
                if(mutation&&!method.name().equals("DELETE")&&!path.endsWith("/publish"))op.getResponses().putIfAbsent("413",new ApiResponse().description("PAYLOAD_TOO_LARGE"));
                if(path.startsWith("/api/v2/attachments"))op.getResponses().putIfAbsent("503",new ApiResponse().description("STORAGE_UNAVAILABLE"));
                if(mutation){op.getResponses().putIfAbsent("409",new ApiResponse().description("State conflicts with this mutation"));op.getResponses().putIfAbsent("415",new ApiResponse().description("Unsupported request media type"));}
                op.getResponses().get("400").setDescription("VALIDATION_ERROR"+(!mutation&&path.endsWith("/documents")?" or INVALID_CURSOR":""));
                op.getResponses().get("404").setDescription("RESOURCE_NOT_FOUND: absent or inaccessible resource; PUT includes missing or other-Document attachment IDs, before deletion conflicts");
                op.getResponses().get("500").setDescription("INTERNAL_ERROR: includes inconsistent saved attachment metadata; never treat failed restoration as an empty registry");
                if(mutation) {
                    String conflicts="REVISION_CONFLICT";
                    if(method.name().equals("PUT"))conflicts+=", ATTACHMENT_DELETION_PENDING";
                    if(path.equals("/api/v2/attachments/{attachmentId}"))conflicts+=", ATTACHMENT_IN_USE, ATTACHMENT_DELETION_PENDING";
                    op.getResponses().get("409").setDescription(conflicts);
                    op.getResponses().get("415").setDescription("UNSUPPORTED_MEDIA_TYPE: HTTP request media type, not file allowlist");
                }
                if(op.getParameters()!=null)for(var p:op.getParameters()) {
                    if("path".equals(p.getIn()))p.setSchema(new StringSchema().format("uuid"));
                    if(p.getName().equals("limit"))p.setSchema(new IntegerSchema().minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100))._default(20));
                }
                op.getResponses().forEach((code,response)->{
                    response.addHeaderObject("Cache-Control",new Header().schema(new StringSchema()._enum(List.of("no-store"))));
                    if(code.startsWith("4")||code.startsWith("5"))response.setContent(new Content().addMediaType("application/json",new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError"))));
                });
            });
        });
    };}
}
