package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GitHubIssueQueryPersistence implements GitHubIssueQueries {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final GitHubSourceCursor cursors;
    public GitHubIssueQueryPersistence(JdbcTemplate jdbc, JsonMapper json, GitHubSourceCursor cursors) { this.jdbc = jdbc; this.json = json; this.cursors = cursors; }
    @Override public Issues issues(UUID user, UUID link, Filter filter, String cursor, Instant now) {
        var scope = scope(user, link, now); String binding = json.writeValueAsString(filter);
        long after = cursors.decode(link, version(scope), binding, cursor);
        StringBuilder where = new StringBuilder(" from github_issue_snapshots where link_id=? and available");
        List<Object> args = new ArrayList<>(); args.add(link);
        if (filter.state() != null) { where.append(" and state=?"); args.add(filter.state()); }
        if (!filter.labelIds().isEmpty()) { where.append(" and label_ids @> ?::jsonb"); args.add(json.writeValueAsString(filter.labelIds())); }
        if (!filter.assigneeIds().isEmpty()) {
            where.append(" and (");
            for (int i = 0; i < filter.assigneeIds().size(); i++) {
                if (i > 0) where.append(" or "); where.append("assignee_ids @> ?::jsonb"); args.add("[" + filter.assigneeIds().get(i) + "]");
            }
            where.append(")");
        }
        long total = jdbc.queryForObject("select count(*)" + where, Long.class, args.toArray());
        args.add(after); args.add(filter.limit() + 1);
        var rows = jdbc.queryForList("select *" + where + " and issue_id>? order by issue_id limit ?", args.toArray());
        var items = rows.stream().limit(filter.limit()).map(this::issue).toList();
        String next = rows.size() > filter.limit() ? cursors.encode(link, version(scope), binding, items.getLast().id()) : null;
        return new Issues(items, total, next, coverage(scope));
    }
    @Override public Labels labels(UUID user, UUID link, int limit, String cursor, Instant now) {
        if (limit < 1 || limit > 100) throw new GitHubException("VALIDATION_ERROR");
        var scope = scope(user, link, now); String binding = "labels:" + limit;
        long after = cursors.decode(link, version(scope), binding, cursor);
        long total = jdbc.queryForObject("select count(*) from github_label_snapshots where link_id=? and available", Long.class, link);
        var rows = jdbc.queryForList("select label_id,name,color from github_label_snapshots where link_id=? and available and label_id>? order by label_id limit ?", link, after, limit + 1);
        var items = rows.stream().limit(limit).map(row -> new GitHubSourceClient.Label(number(row,"label_id"), (String)row.get("name"), (String)row.get("color"))).toList();
        return new Labels(items, total, rows.size() > limit ? cursors.encode(link, version(scope), binding, items.getLast().id()) : null);
    }
    @Override public SyncStatus status(UUID user, UUID link, Instant now) {
        var scope = scope(user, link, now);
        var jobs = jdbc.queryForList("select * from github_sync_jobs where link_id=? order by created_at desc,id desc limit 1", link);
        GitHubSyncStore.Job job = null;
        if (!jobs.isEmpty()) {
            var row = jobs.getFirst(); job = new GitHubSyncStore.Job((UUID)row.get("id"), link, (String)row.get("state"), (String)row.get("stage"),
                    (int)number(row,"page"), (int)number(row,"attempts"), (String)row.get("error_code"), instant(row,"created_at"), instant(row,"updated_at"),
                    (int)number(row,"completed_count"),(int)number(row,"failed_count"),List.of(),(String)row.get("kind"));
        }
        Timestamp lastApplied=jdbc.queryForObject("select max(last_applied_at) from github_task_sources where link_id=?",Timestamp.class,link);
        return new SyncStatus(coverage(scope), job,(boolean)scope.get("paused"),(String)scope.get("project_status"),
                Boolean.TRUE.equals(jdbc.queryForObject("select baseline_ready from github_import_settings where link_id=?",Boolean.class,link)),
                lastApplied==null?null:lastApplied.toInstant(),latest(link,true),latest(link,false));
    }
    private GitHubSyncStore.Job latest(UUID link,boolean collection){
        var rows=jdbc.queryForList("select * from github_sync_jobs where link_id=? and kind "+(collection?"in":"not in")+" ('collect','issue') order by created_at desc,id desc limit 1",link);
        if(rows.isEmpty())return null;var r=rows.getFirst();return new GitHubSyncStore.Job((UUID)r.get("id"),link,(String)r.get("state"),(String)r.get("stage"),(int)number(r,"page"),(int)number(r,"attempts"),(String)r.get("error_code"),instant(r,"created_at"),instant(r,"updated_at"),(int)number(r,"completed_count"),(int)number(r,"failed_count"),List.of(),(String)r.get("kind"));
    }
    private Map<String,Object> scope(UUID user, UUID link, Instant now) {
        var rows = jdbc.queryForList("""
                select l.generation,l.visibility_version,l.status,l.paused,p.status as project_status,l.last_successful_sync_at,a.access_version,a.token_version from github_repository_links l
                join projects p on p.id=l.project_id
                join github_accounts a on a.user_id=l.user_id where l.id=? and l.user_id=? and a.status='connected'
                and l.github_user_id=a.github_user_id and l.access_status='verified' and l.access_account_version=a.token_version
                and l.access_expires_at>? and l.status not in ('disconnecting','disconnected')
                """, link, user, Timestamp.from(now));
        if (rows.isEmpty()) throw new GitHubException("RESOURCE_NOT_FOUND");
        return rows.getFirst();
    }
    private String version(Map<String,Object> scope) { return scope.get("access_version") + ":" + scope.get("token_version") + ":" + scope.get("generation")+":"+scope.get("visibility_version"); }
    private Coverage coverage(Map<String,Object> scope) { return new Coverage(scope.get("last_successful_sync_at") == null ? "collecting" : "initial_scope_collected", instant(scope,"last_successful_sync_at"), 90); }
    private GitHubSourceClient.Issue issue(Map<String,Object> row) {
        return new GitHubSourceClient.Issue(number(row,"issue_id"), number(row,"issue_number"), (String)row.get("title"), (String)row.get("body"), (String)row.get("state"),
                (String)row.get("state_reason"), (String)row.get("html_url"), ids(row.get("label_ids")), ids(row.get("assignee_ids")), (String)row.get("content_hash"),
                (boolean)row.get("content_overflow"), instant(row,"source_updated_at"));
    }
    private List<Long> ids(Object value) { List<Long> ids = new ArrayList<>(); for (var node : json.readTree(value.toString())) ids.add(node.asLong()); return List.copyOf(ids); }
    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private static Instant instant(Map<String,Object> row, String key) { return row.get(key) == null ? null : ((Timestamp)row.get(key)).toInstant(); }
}
