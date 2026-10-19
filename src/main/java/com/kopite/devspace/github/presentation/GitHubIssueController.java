package com.kopite.devspace.github.presentation;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.github.application.GitHubIssueService;
import com.kopite.devspace.github.application.port.GitHubIssueQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v2/projects/{projectId}/github/repositories/{linkId}")
@Tag(name = "GitHub Issues") @SecurityRequirement(name = "sessionCookie")
public class GitHubIssueController {
    private final GitHubIssueService service;
    public GitHubIssueController(GitHubIssueService service) { this.service = service; }
    @GetMapping("/issues") @Operation(summary = "Read collected Issues with access-scoped pagination", description = "Labels match ALL; assignees match ANY; groups combine with AND. Counts cover collected data only. PRs are excluded.")
    public ResponseEntity<GitHubIssueQueries.Issues> issues(Authentication auth, @PathVariable UUID projectId, @PathVariable UUID linkId,
            @RequestParam(required = false) String state, @RequestParam(required = false) List<Long> labelIds,
            @RequestParam(required = false) List<Long> assigneeIds, @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return ok(service.issues(user(auth), projectId, linkId, new GitHubIssueQueries.Filter(state, labelIds, assigneeIds, limit), cursor));
    }
    @GetMapping("/labels") @Operation(summary = "Read collected repository labels")
    public ResponseEntity<GitHubIssueQueries.Labels> labels(Authentication auth, @PathVariable UUID projectId, @PathVariable UUID linkId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return ok(service.labels(user(auth), projectId, linkId, limit, cursor));
    }
    @GetMapping("/sync-status") @Operation(summary = "Read collection coverage and latest job")
    public ResponseEntity<GitHubIssueQueries.SyncStatus> status(Authentication auth, @PathVariable UUID projectId, @PathVariable UUID linkId) {
        return ok(service.status(user(auth), projectId, linkId));
    }
    private static UUID user(Authentication auth) { return ((InternalUserPrincipal)auth.getPrincipal()).userId(); }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
