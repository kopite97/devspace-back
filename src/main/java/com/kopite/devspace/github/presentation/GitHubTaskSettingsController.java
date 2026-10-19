package com.kopite.devspace.github.presentation;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.github.application.GitHubTaskSettingsService;
import com.kopite.devspace.github.application.port.GitHubTaskSettingsStore;
import com.kopite.devspace.github.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.*;

@RestController @RequiredArgsConstructor @SecurityRequirement(name="sessionCookie") @Tag(name="GitHub Task settings")
@RequestMapping("/api/v2/projects/{projectId}/github/repositories/{linkId}")
public class GitHubTaskSettingsController {
    private final GitHubTaskSettingsService service;
    private final com.kopite.devspace.github.application.GitHubTaskWorkService work;
    public record ImportRequest(@Positive long revision,@NotNull @Size(min=1,max=100) List<@NotNull @Positive Long> issueIds) {}
    public record SyncSettings(@Positive long revision,@NotNull Boolean paused) {}
    @PatchMapping("/sync-settings") @Operation(summary="Pause or resume Task application",description="Source collection and access revocation continue. Resume establishes a new baseline and reconciles existing Tasks; preview and explicitly import existing candidates.")
    public ResponseEntity<GitHubTaskSettingsStore.Settings> pause(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID linkId,@Valid @RequestBody SyncSettings request){return ok(service.pause(user(auth),projectId,linkId,request.revision(),request.paused()));}
    @PostMapping("/task-imports") @Operation(summary="Import selected Issues as new Tasks",description="Requires revision and Idempotency-Key. Asynchronous per-item results; source is refreshed after submission. No existing local Task linking.")
    public ResponseEntity<GitHubSyncController.AcceptedJob> requestImport(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID linkId,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ImportRequest request){return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new GitHubSyncController.AcceptedJob(work.request(user(auth),projectId,linkId,request.revision(),request.issueIds(),key)));}
    public record ImportSettings(@Positive long revision,@NotNull Boolean enabled,@NotNull @Size(max=20) List<@Positive Long> labelIds,@NotNull @Size(max=20) List<@Positive Long> assigneeIds) {}
    public record Mapping(@Positive long revision,Long todoLabelId,Long doingLabelId,Long doneLabelId) { GitHubTaskMapping mapping(){return new GitHubTaskMapping(todoLabelId,doingLabelId,doneLabelId);} }
    public record ImportPreview(@Size(min=1,max=100) List<@Positive Long> issueIds,@Min(1) @Max(100) Integer limit,@PositiveOrZero Long afterIssueId) {}
    public record MappingPreview(Long todoLabelId,Long doingLabelId,Long doneLabelId,@Min(1) @Max(100) Integer limit,@PositiveOrZero Long afterIssueId) {}
    @GetMapping({"/import-settings","/status-mapping"}) @Operation(summary="Read import criteria, baseline and label mapping")
    public ResponseEntity<GitHubTaskSettingsStore.Settings> get(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID linkId){return ok(service.get(user(auth),projectId,linkId));}
    @PutMapping("/import-settings") @Operation(summary="Change automatic import criteria",description="Revision required. Starts a new baseline; already matching Issues require explicit selection.")
    public ResponseEntity<GitHubTaskSettingsStore.Settings> configure(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID linkId,@Valid @RequestBody ImportSettings request){return ok(service.configure(user(auth),projectId,linkId,request.revision(),request.enabled(),new GitHubImportCriteria(request.labelIds(),request.assigneeIds())));}
    @PutMapping("/status-mapping") @Operation(summary="Change ID-based Task status mapping",description="Distinct existing label IDs; nullable fields clear mappings. Existing Tasks are reconciled asynchronously.")
    public ResponseEntity<GitHubTaskSettingsStore.Settings> mapping(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID linkId,@Valid @RequestBody Mapping request){return ok(service.mapping(user(auth),projectId,linkId,request.revision(),request.mapping()));}
    @PostMapping("/task-import-previews") @Operation(summary="Preview selected Issues or current matching candidates",description="Read-only bounded preview; afterIssueId is a seek position, no cross-page snapshot guarantee. Current access rechecked on every request.")
    public ResponseEntity<GitHubTaskSettingsStore.Preview> preview(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID linkId,@Valid @RequestBody ImportPreview request){return ok(service.preview(user(auth),projectId,linkId,request.issueIds(),null,request.limit()==null?20:request.limit(),request.afterIssueId()));}
    @PostMapping("/status-mapping-previews") @Operation(summary="Preview a candidate mapping on imported Tasks without changing settings")
    public ResponseEntity<GitHubTaskSettingsStore.Preview> mappingPreview(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID linkId,@Valid @RequestBody MappingPreview request){return ok(service.preview(user(auth),projectId,linkId,null,new GitHubTaskMapping(request.todoLabelId(),request.doingLabelId(),request.doneLabelId()),request.limit()==null?20:request.limit(),request.afterIssueId()));}
    private static UUID user(Authentication auth){return ((InternalUserPrincipal)auth.getPrincipal()).userId();}
    private static <T> ResponseEntity<T> ok(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
