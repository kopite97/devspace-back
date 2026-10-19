package com.kopite.devspace.github.presentation;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.github.application.GitHubSyncService;
import com.kopite.devspace.github.application.port.GitHubSyncStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @Tag(name = "GitHub synchronization") @SecurityRequirement(name = "sessionCookie")
public class GitHubSyncController {
    private final GitHubSyncService service;
    public GitHubSyncController(GitHubSyncService service) { this.service = service; }
    public record AcceptedJob(UUID jobId) {}
    public record FetchIssue(@jakarta.validation.constraints.Positive long issueNumber) {}
    @PostMapping("/api/v2/projects/{projectId}/github/repositories/{linkId}/issue-fetches")
    @Operation(summary = "Fetch an Issue by number, including older closed Issues", description = "Bounded asynchronous lookup, shares manual synchronization cooldown and idempotency scope. PRs cannot become Issues.")
    public ResponseEntity<AcceptedJob> fetch(Authentication auth, @PathVariable UUID projectId, @PathVariable UUID linkId,
            @RequestHeader("Idempotency-Key") String key, @jakarta.validation.Valid @RequestBody FetchIssue request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new AcceptedJob(service.requestIssue(user(auth), projectId, linkId, request.issueNumber(), key)));
    }
    @PostMapping("/api/v2/projects/{projectId}/github/repositories/{linkId}/sync-requests")
    @Operation(summary = "Request bounded asynchronous source reconciliation", description = "Requires CSRF and Idempotency-Key; 60-second cooldown. Task pause remains effective.")
    public ResponseEntity<AcceptedJob> request(Authentication auth, @PathVariable UUID projectId, @PathVariable UUID linkId, @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new AcceptedJob(service.request(user(auth), projectId, linkId, key)));
    }
    @GetMapping("/api/v2/integrations/github/jobs/{jobId}") @Operation(summary = "Read an owned GitHub background job")
    public ResponseEntity<GitHubSyncStore.Job> job(Authentication auth, @PathVariable UUID jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.job(user(auth), jobId));
    }
    private static UUID user(Authentication auth) { return ((InternalUserPrincipal)auth.getPrincipal()).userId(); }
}
