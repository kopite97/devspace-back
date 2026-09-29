package com.kopite.devspace.journal.presentation;
import com.kopite.devspace.journal.application.JournalDocumentCollectionService;
import com.kopite.devspace.document.application.*;
import com.kopite.devspace.document.presentation.DocumentHttp;
import com.kopite.devspace.document.presentation.dto.DocumentRequests;
import com.kopite.devspace.global.response.WorkspaceResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping(value="/api/v2/journals/{journalId}/documents",produces="application/json")
@Tag(name="Journal Documents") @SecurityRequirement(name="sessionCookie")
public class JournalDocumentController {
    private final JournalDocumentCollectionService documents;
    @PostMapping(consumes="application/json") @Operation(summary="Create a DRAFT in this Journal",description="Empty title/content are allowed. No creation replay; retry after timeout may create another Document.")
    @ApiResponse(responseCode="201",description="Document committed")
    public ResponseEntity<DocumentSnapshot> create(Authentication a,@PathVariable String journalId,@Valid @RequestBody DocumentRequests.Create body,HttpServletRequest r){DocumentHttp.query(r);var result=documents.create(DocumentHttp.user(a),DocumentRequests.uuid(journalId,"journalId"),body.title(),body.content().toString());return WorkspaceResponses.created(result,result.dataRevision());}
    @GetMapping @Operation(summary="List this Journal's Documents",description="Summaries only. Stable createdAt/id descending cursor; count/page share a snapshot, pages do not.") @ApiResponse(responseCode="200",description="Document summary page")
    public ResponseEntity<DocumentQueryService.Page> list(Authentication a,@PathVariable String journalId,@RequestParam(defaultValue="20")String limit,@RequestParam(required=false)String cursor,HttpServletRequest r){DocumentHttp.query(r,"limit","cursor");var result=documents.list(DocumentHttp.user(a),DocumentRequests.uuid(journalId,"journalId"),DocumentHttp.limit(limit),cursor);return WorkspaceResponses.ok(result,result.dataRevision());}
}
