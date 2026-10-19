package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.domain.GitHubRateLimitException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;

/** Short durable credential leases; no transaction remains open during HTTP. */
@Repository @Transactional
public class GitHubHttpDispatch {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public GitHubHttpDispatch(JdbcTemplate jdbc, Clock projectClock) { this.jdbc = jdbc; this.clock = projectClock; }
    public UUID acquire(String hash) {
        Instant now = clock.instant(); UUID claim = UUID.randomUUID();
        var rows = jdbc.queryForList("""
                insert into github_http_dispatch(credential_hash,claim,lease_until,not_before,touched_at) values(?,?,?,?,?)
                on conflict(credential_hash) do update set claim=excluded.claim,lease_until=excluded.lease_until,touched_at=excluded.touched_at
                where (github_http_dispatch.lease_until is null or github_http_dispatch.lease_until<=?) and github_http_dispatch.not_before<=?
                returning claim
                """, hash, claim, ts(now.plusSeconds(20)), ts(now), ts(now), ts(now), ts(now));
        if (rows.isEmpty()) {
            var row = jdbc.queryForMap("select not_before,lease_until from github_http_dispatch where credential_hash=?", hash);
            Instant retry = ((Timestamp)row.get("not_before")).toInstant();
            if (!retry.isAfter(now)) retry = now.plusSeconds(1);
            throw new com.kopite.devspace.github.domain.GitHubRequestDeferredException(retry);
        }
        return claim;
    }
    public void release(String hash, UUID claim, Instant retryAt) {
        Instant now = clock.instant();
        jdbc.update("update github_http_dispatch set claim=null,lease_until=null,not_before=greatest(not_before,?),touched_at=? where credential_hash=? and claim=?",
                ts(retryAt == null ? now : retryAt), ts(now), hash, claim);
        jdbc.update("delete from github_http_dispatch where credential_hash in (select credential_hash from github_http_dispatch where (lease_until is null or lease_until<?) and not_before<? and touched_at<? limit 50)",
                ts(now.minusSeconds(86400)), ts(now.minusSeconds(86400)), ts(now.minusSeconds(86400)));
    }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
