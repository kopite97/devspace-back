package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.GitHubSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false) @EnableScheduling
@ConditionalOnProperty(prefix = "app.github", name = {"enabled", "worker-enabled"}, havingValue = "true")
public class GitHubSyncScheduler {
    private final GitHubSyncService service;
    private final com.kopite.devspace.github.application.GitHubRecoveryService recovery;
    private final com.kopite.devspace.github.application.GitHubTaskWorkService tasks;
    public GitHubSyncScheduler(GitHubSyncService service, com.kopite.devspace.github.application.GitHubRecoveryService recovery,com.kopite.devspace.github.application.GitHubTaskWorkService tasks) { this.service = service; this.recovery = recovery; this.tasks=tasks; }
    @Scheduled(fixedDelayString = "${app.github.worker-delay-ms:1000}", initialDelay = 10000)
    public void tick() { recovery.runOnce(); service.runOnce(); tasks.runOnce(); }
}
