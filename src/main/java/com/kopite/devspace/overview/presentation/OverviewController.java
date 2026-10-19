package com.kopite.devspace.overview.presentation;
import com.kopite.devspace.overview.application.*;
import com.kopite.devspace.overview.presentation.dto.*;
import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.global.response.ApiError;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
@RestController
@RequestMapping(value="/api/v2/overview",produces="application/json")
@RequiredArgsConstructor
@Tag(name="Overview")
@SecurityRequirement(name="sessionCookie")
@ApiResponses({
    @ApiResponse(responseCode="400",description="VALIDATION_ERROR",content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="401",description="AUTH_REQUIRED",content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="403",description="ACCOUNT_DISABLED",content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="404",description="RESOURCE_NOT_FOUND: missing or inaccessible Project",content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="500",description="INTERNAL_ERROR",content=@Content(schema=@Schema(implementation=ApiError.class)))
})
public class OverviewController {
    private final OverviewQueryService queries;
    private final com.kopite.devspace.task.application.port.TaskSourceAccess sources;
    @GetMapping
    @Operation(summary="Read workspace Overview",description="Single read snapshot; category/projectId intersection after ownership validation, including archived Projects. projects.total counts active, archived separately. Category buckets include zero counts and an uncategorized bucket; every Task status is always present. Tasks match unsearched stats with projectStatus=all and deleted=false. Totals cover every matching row independent of pages or widget limits; query/status/projectStatus/deleted/limit/cursor and repeated parameters are not accepted.")
    @ApiResponse(responseCode="200",description="Complete owned Project and Task counts",content=@Content(schema=@Schema(implementation=OverviewResponse.class)))
    public ResponseEntity<OverviewResponse> get(Authentication auth,
        @RequestParam(required=false) @Parameter(schema=@Schema(pattern="all|uncategorized|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",defaultValue="all")) String category,
        @RequestParam(required=false) @Parameter(schema=@Schema(type="string",format="uuid")) String projectId,HttpServletRequest http) {
        http.getParameterMap().forEach((name,values)->{if(!Set.of("category","projectId").contains(name)||values.length!=1)throw new OverviewValidationException(name);});
        var user=((InternalUserPrincipal)auth.getPrincipal()).userId(); sources.prepare(user);
        var result=queries.get(user,new OverviewRequest(category,projectId).filter());
        return com.kopite.devspace.global.response.WorkspaceResponses.ok(OverviewResponse.from(result),result.dataRevision());
    }
}
