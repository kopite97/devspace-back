package com.kopite.devspace.widget.presentation;
import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.widget.application.*;
import com.kopite.devspace.global.response.WorkspaceResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
@RestController @RequiredArgsConstructor @SecurityRequirement(name="sessionCookie")
@io.swagger.v3.oas.annotations.tags.Tag(name="Widgets")
public class WidgetDataController {
    private final WidgetDataService data;
    private final com.kopite.devspace.task.application.port.TaskSourceAccess sources;
    @GetMapping(value="/api/v1/widgets/{id}/data",produces="application/json")
    @Operation(summary="Read typed data using saved Widget configuration",description="Configuration, local data and workspace revision share one RR snapshot. Only board/journal/milestone accept cursor; config changes invalidate it. No filter overrides. Current empty uses typed zero/empty data. Retained missing Category: unavailable/unknown/REFERENCE_MISSING. Deploy: unavailable/unknown/NOT_CONFIGURED. Problem is null on successful current data; unavailable has null data/page. Unexpected failures are 5xx. No-store.")
    @ApiResponse(responseCode="200",description="Typed ready/empty or defined unavailable envelope")
    public ResponseEntity<WidgetDataEnvelope> get(Authentication a,@PathVariable UUID id,@RequestParam(required=false)String cursor,HttpServletRequest request){WidgetHttp.query(request,"cursor");var user=((InternalUserPrincipal)a.getPrincipal()).userId();sources.prepare(user);var s=data.get(user,id,cursor);return WorkspaceResponses.ok(s,s.dataRevision());}
}
