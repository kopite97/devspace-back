package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository @Transactional
public class GitHubRecoveryPersistence implements GitHubRecoveryStore {
    private final JdbcTemplate jdbc;
    private final GitHubSyncStore sync;
    private final GitHubAccessStore access;
    public GitHubRecoveryPersistence(JdbcTemplate jdbc, GitHubSyncStore sync, GitHubAccessStore access) { this.jdbc = jdbc; this.sync = sync; this.access = access; }
    @Override public Optional<Claim> claim(Instant now) {
        var rows = jdbc.queryForList("select * from github_delivery_recovery where id and next_run_at<=? and (lease_until is null or lease_until<=?) for update skip locked", ts(now), ts(now));
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.getFirst();
        if (Boolean.TRUE.equals(row.get("gap_pending"))) {
            var links = jdbc.queryForList("select id from github_repository_links where status not in ('disconnecting','disconnected') and (?::uuid is null or id>?) order by id limit 50", row.get("after_link"), row.get("after_link"));
            for (var link : links) sync.enqueue((UUID)link.get("id"), now);
            jdbc.update("update github_delivery_recovery set after_link=?,gap_pending=?,next_run_at=? where id", links.isEmpty() ? null : links.getLast().get("id"), links.size() == 50, ts(now));
            return Optional.empty();
        }
        var deliveries = jdbc.queryForList("select delivery_id from github_redelivery_requests where state='queued' and available_at<=? order by available_at,delivery_id limit 1", ts(now));
        Long delivery = deliveries.isEmpty() ? null : ((Number)deliveries.getFirst().get("delivery_id")).longValue();
        Instant started = instant(row,"scan_started_at"); if (started == null) started = now;
        UUID nonce = UUID.randomUUID();
        jdbc.update("update github_delivery_recovery set claim=?,lease_until=?,scan_started_at=? where id", nonce, ts(now.plusSeconds(60)), ts(started));
        return Optional.of(new Claim(nonce, (String)row.get("cursor"), instant(row,"watermark"), started, delivery));
    }
    @Override public void accept(Claim claim, GitHubDeliveryClient.Page page, Instant now) {
        if (!owned(claim, now)) return;
        boolean reached = false;
        for (var delivery : page.items()) {
            if (claim.watermark() != null && delivery.deliveredAt().isBefore(claim.watermark().minusSeconds(60))) { reached = true; continue; }
            Integer present = jdbc.queryForObject("select count(*) from github_webhook_inbox where delivery_id=?", Integer.class, delivery.guid());
            if (present != null && present > 0) continue;
            int inserted = jdbc.update("insert into github_redelivery_requests(delivery_id,delivery_guid,available_at) values(?,?,?) on conflict do nothing", delivery.id(), delivery.guid(), ts(now));
            // Reconcile current state as well: redelivery can fail again or target another deployment.
            if (inserted > 0) jdbc.update("update github_delivery_recovery set gap_pending=true,after_link=null where id");
            if (delivery.installation() != null && Set.of("installation","installation_repositories","repository").contains(delivery.event()))
                access.invalidateInstallation(delivery.installation(), delivery.repository(), now);
        }
        boolean done = reached || page.nextCursor() == null;
        boolean gap = done && !reached && (claim.watermark() == null || claim.watermark().isBefore(now.minusSeconds(86400)));
        if (done) jdbc.update("update github_delivery_recovery set watermark=?,scan_started_at=null,cursor=null,gap_pending=gap_pending or ?,claim=null,lease_until=null,next_run_at=?,error_code=null where id",
                ts(claim.startedAt()), gap, ts(now));
        else jdbc.update("update github_delivery_recovery set cursor=?,claim=null,lease_until=null,next_run_at=?,error_code=null where id", page.nextCursor(), ts(now));
        if (done) {
            Integer pending = jdbc.queryForObject("select count(*) from github_redelivery_requests where state='queued' and available_at<=?", Integer.class, ts(now));
            jdbc.update("update github_delivery_recovery set next_run_at=case when gap_pending or ? then ?::timestamptz else ?::timestamptz end where id", pending != null && pending > 0, ts(now), ts(now.plusSeconds(600)));
        }
    }
    @Override public void finishRedelivery(Claim claim, boolean success, Instant retryAt, Instant now) {
        if (!owned(claim, now)) return;
        jdbc.update("update github_redelivery_requests set attempts=attempts+1,state=case when ? then 'completed' when attempts>=7 then 'failed' else 'queued' end,available_at=?,requested_at=? where delivery_id=?",
                success, ts(retryAt), ts(now), claim.redeliveryId());
        jdbc.update("update github_delivery_recovery set claim=null,lease_until=null,next_run_at=? where id", ts(success ? now : retryAt));
    }
    @Override public void fail(Claim claim, String code, Instant retryAt, Instant now) {
        if (!owned(claim, now)) return;
        jdbc.update("update github_delivery_recovery set claim=null,lease_until=null,error_code=?,next_run_at=? where id", code, ts(retryAt));
    }
    private boolean owned(Claim claim, Instant now) {
        return !jdbc.queryForList("select id from github_delivery_recovery where id and claim=? and lease_until>? for update", claim.nonce(), ts(now)).isEmpty();
    }
    private static Instant instant(Map<String,Object> row, String key) { return row.get(key) == null ? null : ((Timestamp)row.get(key)).toInstant(); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
