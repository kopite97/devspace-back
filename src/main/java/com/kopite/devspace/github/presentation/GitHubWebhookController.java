package com.kopite.devspace.github.presentation;

import com.kopite.devspace.github.application.GitHubWebhookService;
import com.kopite.devspace.github.domain.GitHubException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController @Tag(name = "GitHub Webhook")
public class GitHubWebhookController {
    private final GitHubWebhookService service;
    public GitHubWebhookController(GitHubWebhookService service) { this.service = service; }
    @PostMapping("/api/integrations/github/webhooks")
    @Operation(summary = "Receive a GitHub App signed delivery", description = "HMAC SHA-256 over exact request bytes. No browser session/CSRF token is used. Returns 202 only after durable receipt.")
    public ResponseEntity<Void> receive(HttpServletRequest request,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event) throws IOException {
        int max = 2 * 1024 * 1024;
        if (request.getContentLengthLong() > max) throw new GitHubException("GITHUB_PAYLOAD_TOO_LARGE");
        byte[] bytes = request.getInputStream().readNBytes(max + 1);
        if (bytes.length > max) throw new GitHubException("GITHUB_PAYLOAD_TOO_LARGE");
        service.receive(signature, delivery, event, bytes);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }
}
