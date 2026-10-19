package com.kopite.devspace.github.application.port;
import com.kopite.devspace.github.domain.GitHubWebhook;
import java.time.Instant;
public interface GitHubWebhookStore {
    boolean receive(GitHubWebhook webhook, Instant now);
}
