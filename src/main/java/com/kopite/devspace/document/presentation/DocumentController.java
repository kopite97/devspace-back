package com.kopite.devspace.document.presentation;
import com.kopite.devspace.document.application.*;
import com.kopite.devspace.document.presentation.dto.DocumentRequests;
import com.kopite.devspace.global.response.WorkspaceResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping(value="/api/v2/documents",produces="application/json")
@Tag(name="Documents") @SecurityRequirement(name="sessionCookie")
public class DocumentController {
    private final DocumentQueryService queries;
    private final DocumentCommandService commands;
    private final DocumentDeletionService deletion;
    public record DeletedDocument(java.util.UUID deletedId){}
    @DeleteMapping("/{documentId}") @Operation(summary="Delete a Document",description="Success commits metadata deletion and durable file cleanup. Storage failure can delay physical file removal.") @ApiResponse(responseCode="200",description="Document deleted; file cleanup durable")
    public ResponseEntity<DeletedDocument> delete(Authentication a,@PathVariable String documentId,HttpServletRequest r){DocumentHttp.query(r);var result=deletion.delete(DocumentHttp.user(a),DocumentRequests.uuid(documentId,"documentId"));return WorkspaceResponses.ok(new DeletedDocument(result.id()),result.dataRevision());}
    @PutMapping(value="/{documentId}",consumes="application/json") @Operation(summary="Save the complete Document and attachment relationship") @ApiResponse(responseCode="200",description="Document and attachmentIds committed together")
    public ResponseEntity<DocumentSnapshot> update(Authentication a,@PathVariable String documentId,@jakarta.validation.Valid @RequestBody DocumentRequests.Update body,HttpServletRequest r){DocumentHttp.query(r);var result=commands.update(DocumentHttp.user(a),DocumentRequests.uuid(documentId,"documentId"),body.title(),body.content().toString(),body.attachmentIds());return WorkspaceResponses.ok(result,result.dataRevision());}
    @PostMapping("/{documentId}/publish") @Operation(summary="Publish a Document",description="Repeated publication preserves the first publishedAt and performs no mutation. Published Documents remain editable.") @ApiResponse(responseCode="200",description="Published Document")
    public ResponseEntity<DocumentSnapshot> publish(Authentication a,@PathVariable String documentId,HttpServletRequest r,@RequestBody(required=false)byte[] body){DocumentHttp.query(r);if(body!=null&&body.length>0)throw com.kopite.devspace.document.domain.DocumentException.invalid("body");var result=commands.publish(DocumentHttp.user(a),DocumentRequests.uuid(documentId,"documentId"));return WorkspaceResponses.ok(result,result.dataRevision());}
    @GetMapping("/{documentId}") @Operation(summary="Read a Document by its global identity",description="Requires the current owner context. Content is opaque JSON; attachmentIds derive from ATTACHED records.") @ApiResponse(responseCode="200",description="Document detail")
    public ResponseEntity<DocumentSnapshot> get(Authentication a,@PathVariable String documentId,HttpServletRequest r){DocumentHttp.query(r);var result=queries.get(DocumentHttp.user(a),DocumentRequests.uuid(documentId,"documentId"));return WorkspaceResponses.ok(result,result.dataRevision());}
}
