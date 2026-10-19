package com.kopite.devspace.github.presentation;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.github.application.GitHubAuthorizationService;
import com.kopite.devspace.github.domain.GitHubException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/integrations/github")
@Tag(name = "GitHub account")
@SecurityRequirement(name = "sessionCookie")
public class GitHubAccountController {
    private final GitHubAuthorizationService service;
    private final String returnUrl;
    public GitHubAccountController(GitHubAuthorizationService service, @Value("${app.github.frontend-return-url:}") String returnUrl) {
        this.service = service; this.returnUrl = returnUrl;
    }
    @GetMapping("/account") @Operation(summary = "Read GitHub account connection state")
    public ResponseEntity<GitHubAuthorizationService.AccountView> account(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.account(user(auth)));
    }
    @PostMapping("/authorization-requests") @Operation(summary = "Start session-bound GitHub authorization", description = "Requires X-CSRF-Token. Returns a single-use authorization URL valid for ten minutes.")
    public ResponseEntity<GitHubAuthorizationService.AuthorizationRequest> begin(Authentication auth, HttpServletRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.begin(user(auth), request.getSession().getId()));
    }
    @GetMapping("/callback") @Operation(summary = "Complete the current session's GitHub authorization")
    public ResponseEntity<Void> callback(Authentication auth, HttpServletRequest request,
            @RequestParam(required = false) String state, @RequestParam(required = false) String code,
            @RequestParam(required = false) String error) {
        var session = request.getSession(false);
        if (session == null) throw new GitHubException("GITHUB_INVALID_STATE");
        String outcome;
        try {
            service.complete(user(auth), session.getId(), state, code, error); outcome = "connected";
        } catch (GitHubException ex) {
            outcome = switch (ex.code()) {
                case "GITHUB_AUTHORIZATION_DENIED" -> "denied";
                case "GITHUB_ACCOUNT_ALREADY_CONNECTED" -> "account_conflict";
                case "GITHUB_UNAVAILABLE", "GITHUB_RATE_LIMITED", "GITHUB_REAUTHORIZATION_REQUIRED", "GITHUB_INVALID_AUTHORIZATION" -> "failed";
                default -> throw ex;
            };
        }
        return ResponseEntity.status(303).cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .location(URI.create(returnUrl + "?github=" + outcome)).build();
    }
    private static UUID user(Authentication auth) { return ((InternalUserPrincipal) auth.getPrincipal()).userId(); }
    @DeleteMapping("/account") @Operation(summary = "Disconnect GitHub credentials after project connections have been removed")
    public ResponseEntity<Void> disconnect(Authentication auth) {
        service.disconnect(user(auth)); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
