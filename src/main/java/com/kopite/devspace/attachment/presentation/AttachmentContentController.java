package com.kopite.devspace.attachment.presentation;
import com.kopite.devspace.attachment.application.*;
import com.kopite.devspace.document.domain.DocumentException;
import com.kopite.devspace.document.presentation.DocumentHttp;
import com.kopite.devspace.document.presentation.dto.DocumentRequests;
import com.kopite.devspace.global.response.WorkspaceResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
@RestController @SecurityRequirement(name="sessionCookie") @io.swagger.v3.oas.annotations.tags.Tag(name="Attachments")
public class AttachmentContentController {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AttachmentContentController.class);
    private final AttachmentContentQuery query;private final FileStorage storage;private final Semaphore slots;
    public AttachmentContentController(AttachmentContentQuery query,FileStorage storage,@Value("${app.attachment.stream.max-concurrent:16}")int max) {
        if(max<1||max>128)throw new IllegalArgumentException("Invalid content concurrency bound");this.query=query;this.storage=storage;slots=new Semaphore(max);
    }
    @GetMapping("/api/v2/attachments/{attachmentId}/content") @Operation(summary="Read attachment bytes",description="Authenticated TEMP and ATTACHED content. Claimed deletion returns 404. PNG/JPEG inline; PDF/TXT downloaded. No range or cache support.") @ApiResponse(responseCode="200",description="Attachment binary stream")
    public ResponseEntity<StreamingResponseBody> content(Authentication auth,@PathVariable String attachmentId,HttpServletRequest request) {
        DocumentHttp.query(request);var content=query.get(DocumentHttp.user(auth),DocumentRequests.uuid(attachmentId,"attachmentId"));
        String requestId=java.util.UUID.randomUUID().toString();
        if(!slots.tryAcquire())throw new DocumentException("STORAGE_UNAVAILABLE",null);
        InputStream input;try{input=storage.open(content.key());}catch(RuntimeException failure){slots.release();throw failure;}
        var closed=new AtomicBoolean();Runnable close=()->{if(closed.compareAndSet(false,true)){try{input.close();}catch(IOException ignored){}finally{slots.release();}}};
        WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor(this,new CallableProcessingInterceptor(){
            public <T> Object handleTimeout(NativeWebRequest r,Callable<T> task){close.run();log.warn("Attachment stream timed out; requestId={}, attachmentId={}",requestId,attachmentId);return new DocumentException("STORAGE_UNAVAILABLE",null);}
            public <T> Object handleError(NativeWebRequest r,Callable<T> task,Throwable failure){close.run();return RESULT_NONE;}
            public <T> void afterCompletion(NativeWebRequest r,Callable<T> task){close.run();}
        });
        StreamingResponseBody body=out->{try{byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);}catch(IOException failure){log.warn("Attachment stream interrupted; requestId={}, attachmentId={}",requestId,attachmentId);throw failure;}finally{close.run();}};
        var disposition=ContentDisposition.builder(content.type().startsWith("image/")?"inline":"attachment").filename(content.filename(),StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(content.type())).contentLength(content.size())
            .header("X-Request-ID",requestId).header("X-Content-Type-Options","nosniff").header("Content-Disposition",disposition.toString()).header(WorkspaceResponses.REVISION_HEADER,Long.toString(content.revision())).body(body);
    }
}
