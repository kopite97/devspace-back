package com.kopite.devspace.attachment.presentation;
import com.kopite.devspace.attachment.application.*;
import com.kopite.devspace.document.domain.DocumentException;
import com.kopite.devspace.document.presentation.DocumentHttp;
import com.kopite.devspace.document.presentation.dto.DocumentRequests;
import com.kopite.devspace.global.response.WorkspaceResponses;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import java.io.IOException;
@RestController @RequiredArgsConstructor @RequestMapping("/api/v2/attachments")
@Tag(name="Attachments") @SecurityRequirement(name="sessionCookie")
public class AttachmentController {
    private final AttachmentUploadService uploads;
    private final AttachmentCleanupTransactions cleanup;
    private final AttachmentCleanupWorker worker;
    public record DeletedAttachment(java.util.UUID deletedId){}
    @DeleteMapping(value="/{attachmentId}",produces="application/json") @Operation(summary="Delete a TEMP attachment",description="200 means both file and metadata were removed. Storage failure returns 503 and keeps a durable retry claim.") @ApiResponse(responseCode="200",description="File and metadata deleted")
    public ResponseEntity<DeletedAttachment> delete(Authentication a,@PathVariable String attachmentId,jakarta.servlet.http.HttpServletRequest request){DocumentHttp.query(request);var id=DocumentRequests.uuid(attachmentId,"attachmentId");var job=cleanup.claim(DocumentHttp.user(a),id,null);var revision=worker.process(job);return WorkspaceResponses.ok(new DeletedAttachment(id),revision);}
    @PostMapping(consumes="multipart/form-data",produces="application/json")
    @Operation(summary="Upload a TEMP attachment",description="Requires documentId and one file. The binary is durably stored before metadata commits; timeout retries can create duplicates.") @ApiResponse(responseCode="201",description="File and TEMP metadata committed")
    public ResponseEntity<AttachmentSnapshot> upload(Authentication a,@RequestParam String documentId,@RequestPart MultipartFile file,MultipartHttpServletRequest request)throws IOException {
        DocumentHttp.query(request,"documentId");
        if(request.getQueryString()!=null||request.getMultiFileMap().size()!=1||!request.getMultiFileMap().containsKey("file")||request.getFiles("file").size()!=1)throw DocumentException.invalid("file");
        try(var input=file.getInputStream()) {
            var result=uploads.upload(DocumentHttp.user(a),DocumentRequests.uuid(documentId,"documentId"),file.getOriginalFilename(),file.getContentType(),input);
            return WorkspaceResponses.created(result,result.dataRevision());
        }
    }
}
