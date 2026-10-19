package com.kopite.devspace.github.presentation;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.github.application.GitHubConnectionService;
import com.kopite.devspace.github.application.model.GitHubLinkSnapshot;
import com.kopite.devspace.github.application.port.GitHubRepositoryClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @Tag(name = "GitHub repositories") @SecurityRequirement(name = "sessionCookie")
public class GitHubRepositoryController {
    private final GitHubConnectionService service;
    public GitHubRepositoryController(GitHubConnectionService service) { this.service = service; }
    public record ConnectRequest(@Positive long installationId, @Positive long repositoryId) {}
    @GetMapping("/api/v2/integrations/github/installations") @Operation(summary = "List installations accessible to the connected GitHub user")
    public ResponseEntity<GitHubRepositoryClient.Page<GitHubRepositoryClient.Installation>> installations(Authentication auth,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit) {
        return ok(service.installations(user(auth), page, limit));
    }
    @GetMapping("/api/v2/integrations/github/installations/{installationId}/repositories") @Operation(summary = "List user-accessible installation repositories")
    public ResponseEntity<GitHubRepositoryClient.Page<GitHubRepositoryClient.Repository>> repositories(Authentication auth,
            @PathVariable long installationId, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit) {
        return ok(service.repositories(user(auth), installationId, page, limit));
    }
    @GetMapping("/api/v2/projects/{projectId}/github/repositories") @Operation(summary = "List project GitHub connections", description = "Maximum 100 connections per project. Unverified source names/URLs are omitted.")
    public ResponseEntity<List<GitHubLinkSnapshot>> list(Authentication auth, @PathVariable UUID projectId) { return ok(service.list(user(auth), projectId)); }
    @GetMapping("/api/v2/projects/{projectId}/github/repositories/{linkId}") @Operation(summary = "Read a project GitHub connection")
    public ResponseEntity<GitHubLinkSnapshot> get(Authentication auth, @PathVariable UUID projectId, @PathVariable UUID linkId) { return ok(service.get(user(auth), projectId, linkId)); }
    @PostMapping("/api/v2/projects/{projectId}/github/repositories") @Operation(summary = "Connect a verified repository to a project", description = "Requires X-CSRF-Token and Idempotency-Key. Same key/body returns the same connection after current access verification.")
    public ResponseEntity<GitHubLinkSnapshot> connect(Authentication auth, @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ConnectRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.connect(user(auth), projectId, request.installationId(), request.repositoryId(), key));
    }
    private static UUID user(Authentication auth) { return ((InternalUserPrincipal)auth.getPrincipal()).userId(); }
    private static <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
