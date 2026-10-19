package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.GitHubWebhookStore;
import com.kopite.devspace.github.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;

@Repository
public class GitHubWebhookPersistence implements GitHubWebhookStore {
    private final JdbcTemplate jdbc;
    public GitHubWebhookPersistence(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public boolean receive(GitHubWebhook webhook, Instant now) {
        int inserted = jdbc.update("insert into github_webhook_inbox(delivery_id,event_type,action,installation_id,repository_id,github_user_id,issue_number,payload_hash,payload,available_at,received_at) values(?,?,?,?,?,?,?,?,?,?,?) on conflict(delivery_id) do nothing",
                webhook.delivery(), webhook.type(), webhook.action(), webhook.installation(), webhook.repository(), webhook.githubUser(), webhook.issueNumber(), webhook.hash(), webhook.payload(), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        if (inserted == 0) {
            var row = jdbc.queryForMap("select payload_hash,event_type from github_webhook_inbox where delivery_id=?", webhook.delivery());
            if (!webhook.hash().equals(row.get("payload_hash")) || !webhook.type().equals(row.get("event_type")))
                throw new GitHubException("GITHUB_DELIVERY_CONFLICT");
        }
        return inserted == 1;
    }
}
