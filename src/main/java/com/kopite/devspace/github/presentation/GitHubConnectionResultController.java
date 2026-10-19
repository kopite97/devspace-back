package com.kopite.devspace.github.presentation;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.github.application.GitHubAuthorizationService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Temporary authenticated landing page until the frontend settings screen exists. */
@Hidden
@RestController
public class GitHubConnectionResultController {
    private final GitHubAuthorizationService authorization;
    public GitHubConnectionResultController(GitHubAuthorizationService authorization) { this.authorization = authorization; }

    @GetMapping(value = "/github/connection-result", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> result(Authentication authentication, @RequestParam(defaultValue = "") String github) {
        var user = (InternalUserPrincipal) authentication.getPrincipal();
        boolean connected = github.equals("connected") && authorization.account(user.userId()).status().equals("connected");
        String message = connected ? "GitHub 계정이 연결되었습니다." : "GitHub 계정 연결을 완료하지 못했습니다. 연결을 다시 시작해 주세요.";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
                .header("Referrer-Policy", "no-referrer")
                .body("<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><title>GitHub 연결</title><body><h1>"
                        + message + "</h1><p>이 창을 닫고 개발 환경으로 돌아가세요.</p></body></html>");
    }
}
