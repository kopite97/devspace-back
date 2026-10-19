package com.kopite.devspace.github.infrastructure;
import com.kopite.devspace.github.application.GitHubDetachService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import lombok.RequiredArgsConstructor;

/** Explicit protected-data cleanup remains available when provider integration is disabled. */
@Configuration(proxyBeanMethods=false) @EnableScheduling @RequiredArgsConstructor
@ConditionalOnProperty(prefix="app.github",name="worker-enabled",havingValue="true")
public class GitHubDetachScheduler {
    private final GitHubDetachService service;
    @Scheduled(fixedDelayString="${app.github.worker-delay-ms:1000}",initialDelay=10000)
    public void tick(){service.runOnce();}
}
