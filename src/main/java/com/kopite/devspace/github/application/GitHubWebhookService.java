package com.kopite.devspace.github.application;

import com.kopite.devspace.github.application.port.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.Set;

@Service
public class GitHubWebhookService {
    private final GitHubWebhookDecoder decoder;
    private final GitHubWebhookStore inbox;
    private final GitHubAccessStore access;
    private final Clock clock;
    public GitHubWebhookService(GitHubWebhookDecoder decoder, GitHubWebhookStore inbox, GitHubAccessStore access, Clock projectClock) {
        this.decoder = decoder; this.inbox = inbox; this.access = access; this.clock = projectClock;
    }
    @Transactional
    public void receive(String signature, String delivery, String event, byte[] body) {
        try {
        var webhook = decoder.decode(signature, delivery, event, body); var now = clock.instant();
        if (!inbox.receive(webhook, now)) return;
        if (webhook.installation() != null && Set.of("installation", "installation_repositories", "repository").contains(webhook.type()))
            access.invalidateInstallation(webhook.installation(), webhook.type().equals("repository") ? webhook.repository() : null, now);
        if (webhook.type().equals("github_app_authorization") && webhook.action().equals("revoked") && webhook.githubUser() != null)
            access.invalidateUser(webhook.githubUser(), now);
        } catch (com.kopite.devspace.github.domain.GitHubException ex) { throw ex; }
        catch (RuntimeException ex) { throw new com.kopite.devspace.github.domain.GitHubException("GITHUB_UNAVAILABLE"); }
    }
}
