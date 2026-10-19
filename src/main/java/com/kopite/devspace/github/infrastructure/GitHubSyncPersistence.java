package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.GitHubException;
import com.kopite.devspace.project.domain.ProjectRepository;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository @Transactional
public class GitHubSyncPersistence implements GitHubSyncStore {
    private final JdbcTemplate jdbc;
    private final PersonalWorkspaceRepository workspaces;
    private final ProjectRepository projects;
    private final JsonMapper json;
    public GitHubSyncPersistence(JdbcTemplate jdbc, PersonalWorkspaceRepository workspaces, ProjectRepository projects, JsonMapper json) {
        this.jdbc = jdbc; this.workspaces = workspaces; this.projects = projects; this.json = json;
    }
    @Override public UUID enqueue(UUID link, Instant now) {
        UUID id = UUID.randomUUID();
        var rows = jdbc.queryForList("""
                insert into github_sync_jobs(id,link_id,scan_id,generation,available_at,created_at,updated_at)
                select ?,id,?,generation,?,?,? from github_repository_links where id=? and status not in ('disconnecting','disconnected')
                on conflict(link_id) where kind='collect' and state in ('queued','running')
                do update set requested_again=true,updated_at=excluded.updated_at returning id
                """, id, UUID.randomUUID(), ts(now), ts(now), ts(now), link);
        if (rows.isEmpty()) throw new GitHubException("GITHUB_LINK_INACTIVE");
        return (UUID) rows.getFirst().get("id");
    }
    @Override public UUID request(UUID user, UUID link, String key, Instant now) {
        return requestWork(user, link, key, "collect", now);
    }
    @Override public UUID requestIssue(UUID user, UUID link, long number, String key, Instant now) {
        if (number < 1 || number > 9007199254740991L) throw new GitHubException("VALIDATION_ERROR");
        return requestWork(user, link, key, "issue:" + number, now);
    }
    @Override public UUID enqueueIssue(UUID link, long number, Instant now) {
        var rows = jdbc.queryForList("""
                insert into github_sync_jobs(id,link_id,kind,stage,scan_id,generation,available_at,created_at,updated_at)
                select ?,id,'issue',?,?,generation,?,?,? from github_repository_links where id=? and status not in ('disconnecting','disconnected')
                on conflict(link_id,stage) where kind='issue' and state in ('queued','running') do update set requested_again=true,updated_at=excluded.updated_at returning id
                """, UUID.randomUUID(), "issue:" + number, UUID.randomUUID(), ts(now), ts(now), ts(now), link);
        if (rows.isEmpty()) throw new GitHubException("GITHUB_LINK_INACTIVE");
        return (UUID)rows.getFirst().get("id");
    }
    private UUID requestWork(UUID user, UUID link, String key, String operation, Instant now) {
        if (key == null || !key.matches("[!-~]{1,128}")) throw new GitHubException("VALIDATION_ERROR");
        workspaces.lockByOwnerId(user).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        var rows = jdbc.queryForList("select * from github_repository_links where id=? and user_id=? for update", link, user);
        if (rows.isEmpty()) throw new GitHubException("RESOURCE_NOT_FOUND");
        requireAccess(link, user, now);
        jdbc.update("delete from github_sync_replays where user_id=? and expires_at<=?", user, ts(now));
        var replay = jdbc.queryForList("select job_id,operation from github_sync_replays where user_id=? and link_id=? and request_key=?", user, link, key);
        if (!replay.isEmpty()) {
            if (!operation.equals(replay.getFirst().get("operation"))) throw new GitHubException("IDEMPOTENCY_KEY_REUSED");
            return (UUID) replay.getFirst().get("job_id");
        }
        Timestamp previous = (Timestamp) rows.getFirst().get("last_manual_sync_at");
        if (previous != null && previous.toInstant().plusSeconds(60).isAfter(now)) throw new com.kopite.devspace.github.domain.GitHubRateLimitException(previous.toInstant().plusSeconds(60));
        UUID id = operation.equals("collect") ? enqueue(link, now) : enqueueIssue(link, Long.parseLong(operation.substring(6)), now);
        jdbc.update("update github_repository_links set last_manual_sync_at=? where id=?", ts(now), link);
        jdbc.update("insert into github_sync_replays(user_id,link_id,request_key,job_id,expires_at,operation) values(?,?,?,?,?,?)", user, link, key, id, ts(now.plusSeconds(86400)), operation);
        return id;
    }
    @Override public Optional<Work> claim(Instant now) {
        jdbc.update("update github_sync_jobs set state='failed',error_code='GITHUB_RETRY_EXHAUSTED',claim=null,lease_until=null,updated_at=? where state='running' and lease_until<=? and attempts>=8", ts(now), ts(now));
        var rows = jdbc.queryForList("""
                select j.*,l.user_id,l.workspace_id,l.project_id,l.installation_id,l.repository_id,l.generation as link_generation
                from github_sync_jobs j join github_repository_links l on l.id=j.link_id
                left join github_dispatch_limits d on d.installation_id=l.installation_id
                where j.kind in ('collect','issue') and j.state in ('queued','running') and j.available_at<=?
                and (j.lease_until is null or j.lease_until<=?) and l.status not in ('disconnecting','disconnected')
                and (d.not_before is null or d.not_before<=?) and (d.lease_until is null or d.lease_until<=?)
                order by j.available_at,j.created_at,j.id for update of j skip locked limit 1
                """, ts(now), ts(now), ts(now), ts(now));
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.getFirst(); UUID id = (UUID) row.get("id"), nonce = UUID.randomUUID(); long installation = number(row, "installation_id");
        jdbc.update("insert into github_dispatch_limits(installation_id,not_before) values(?,?) on conflict do nothing", installation, ts(now));
        if (jdbc.update("update github_dispatch_limits set claim=?,lease_until=? where installation_id=? and not_before<=? and (lease_until is null or lease_until<=?)",
                nonce, ts(now.plusSeconds(60)), installation, ts(now), ts(now)) == 0) return Optional.empty();
        long generation = number(row, "link_generation"); String stage = (String) row.get("stage"); int page = ((Number)row.get("page")).intValue(); UUID scan = (UUID)row.get("scan_id");
        if (number(row, "generation") != generation) { if (!stage.startsWith("issue:")) stage = "labels"; page = 1; scan = UUID.randomUUID(); }
        int attempts = ((Number)row.get("attempts")).intValue() + 1;
        jdbc.update("update github_sync_jobs set state='running',claim=?,lease_until=?,generation=?,stage=?,page=?,scan_id=?,attempts=?,updated_at=? where id=?",
                nonce, ts(now.plusSeconds(60)), generation, stage, page, scan, attempts, ts(now), id);
        return Optional.of(new Work(id, (UUID)row.get("link_id"), (UUID)row.get("workspace_id"), (UUID)row.get("project_id"), (UUID)row.get("user_id"),
                installation, number(row,"repository_id"), generation, nonce, scan, stage, page, attempts, ((Timestamp)row.get("created_at")).toInstant(), ((Number)row.get("page_size")).intValue()));
    }
    @Override public void apply(Work work, GitHubSourceClient.Page page, Instant now) {
        workspaces.lockByOwnerId(work.user()).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        projects.lockOwned(work.workspace(), work.project()).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        jdbc.queryForList("select user_id from github_accounts where user_id=? for update", work.user());
        var link = jdbc.queryForMap("select generation,status from github_repository_links where id=? for no key update", work.link());
        var job = ownedClaim(work, now);
        if (job == null) return;
        if (number(link, "generation") != work.generation() || Set.of("disconnecting", "disconnected").contains(link.get("status"))) {
            finishAttempt(work, "queued", work.stage(), work.page(), "GITHUB_SOURCE_CHANGED", now, now); return;
        }
        requireAccess(work.link(), work.user(), now);
        boolean visibilityChanged = false;
        for (long number : page.unavailableNumbers()) visibilityChanged |= jdbc.update("update github_issue_snapshots set available=false,collected_at=?,collection_version=collection_version+1 where link_id=? and issue_number=? and available", ts(now), work.link(), number) > 0;
        for (var issue : page.issues()) visibilityChanged |= Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from github_issue_snapshots where link_id=? and issue_id=? and not available and source_updated_at<=?)", Boolean.class, work.link(), issue.id(), ts(issue.updatedAt())));
        if (visibilityChanged) jdbc.update("update github_repository_links set visibility_version=visibility_version+1 where id=?", work.link());
        for (var label : page.labels()) jdbc.update("""
                insert into github_label_snapshots(link_id,label_id,name,color,scan_id) values(?,?,?,?,?)
                on conflict(link_id,label_id) do update set name=excluded.name,color=excluded.color,scan_id=excluded.scan_id,available=true
                """, work.link(), label.id(), label.name(), label.color(), work.scan());
        for (var issue : page.issues()) jdbc.update("""
                insert into github_issue_snapshots(link_id,issue_id,issue_number,title,body,state,state_reason,html_url,label_ids,assignee_ids,content_hash,content_overflow,source_updated_at,collected_at)
                values(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?)
                on conflict(link_id,issue_id) do update set issue_number=excluded.issue_number,title=excluded.title,body=excluded.body,state=excluded.state,
                state_reason=excluded.state_reason,html_url=excluded.html_url,label_ids=excluded.label_ids,assignee_ids=excluded.assignee_ids,
                content_hash=excluded.content_hash,content_overflow=excluded.content_overflow,available=true,source_updated_at=excluded.source_updated_at,collected_at=excluded.collected_at,collection_version=github_issue_snapshots.collection_version+1
                where excluded.source_updated_at>=github_issue_snapshots.source_updated_at
                """, work.link(), issue.id(), issue.number(), issue.title(), issue.body(), issue.state(), issue.reason(), issue.htmlUrl(),
                json.writeValueAsString(issue.labelIds()), json.writeValueAsString(issue.assigneeIds()), issue.contentHash(), issue.contentOverflow(), ts(issue.updatedAt()), ts(now));
        String stage = work.stage(); int next = work.page() + 1;
        for (var issue : page.issues()) {
            // Changes observed during a pending baseline belong to that baseline, even if its scan already passed this ID.
            jdbc.update("""
                    insert into github_import_observations(link_id,issue_id,generation,matched)
                    select i.link_id,i.issue_id,s.generation,i.label_ids @> s.label_ids and
                    (s.assignee_ids='[]'::jsonb or exists(select 1 from jsonb_array_elements(i.assignee_ids) a where s.assignee_ids @> jsonb_build_array(a)))
                    from github_issue_snapshots i join github_import_settings s on s.link_id=i.link_id where i.link_id=? and i.issue_id=? and not s.baseline_ready
                    on conflict(link_id,issue_id) do update set generation=excluded.generation,matched=excluded.matched
                    """,work.link(),issue.id());
            enqueueTaskWork(work.link(), "apply", "issue:" + issue.id(), now);
        }
        if (!page.more()) {
            if (stage.equals("labels")) {
                jdbc.update("update github_label_snapshots set available=false where link_id=? and scan_id<>?", work.link(), work.scan()); stage = "open";
                enqueueTaskWork(work.link(), "mapping", "0", now);
            } else if (stage.equals("open")) stage = "closed";
            else if (stage.equals("closed")) stage = "verify";
            else stage = "done";
            next = 1;
        }
        if (stage.equals("done")) {
            if (!work.stage().startsWith("issue:")) jdbc.update("update github_repository_links set status='ready',last_successful_sync_at=? where id=?", ts(now), work.link());
            boolean unavailable = !page.unavailableNumbers().isEmpty();
            finishAttempt(work, unavailable ? "failed" : "completed", stage, next, unavailable ? "GITHUB_ISSUE_UNAVAILABLE" : null, now, now);
            if (Boolean.TRUE.equals(job.get("requested_again"))) {
                if (work.stage().startsWith("issue:")) enqueueIssue(work.link(), Long.parseLong(work.stage().substring(6)), now);
                else enqueue(work.link(), now);
            }
        } else finishAttempt(work, "queued", stage, next, null, now, now);
    }
    @Override public void fail(Work work, String code, Instant retryAt, Instant now) {
        if (ownedClaim(work, now) == null) return;
        if (code.equals("GITHUB_RESPONSE_TOO_LARGE") && work.pageSize() > 1 && !work.stage().startsWith("issue:")) {
            jdbc.update("update github_sync_jobs set page_size=? where id=?", Math.max(1, work.pageSize() / 2), work.id());
            finishAttempt(work, "queued", work.stage(), 1, code, retryAt, now); return;
        }
        finishAttempt(work, work.attempts() >= 8 ? "failed" : "queued", work.stage(), work.page(), code, retryAt, now);
        if (code.equals("GITHUB_RATE_LIMITED")) jdbc.update("update github_dispatch_limits set not_before=greatest(not_before,?) where installation_id=?", ts(retryAt), work.installation());
    }
    private void enqueueTaskWork(UUID link,String kind,String stage,Instant now) {
        jdbc.update("""
                insert into github_sync_jobs(id,link_id,kind,stage,scan_id,generation,available_at,created_at,updated_at)
                select ?,l.id,?,?,?,l.generation,?,?,? from github_repository_links l where l.id=?
                and (?<>'apply' or (not l.paused and exists(select 1 from projects p where p.id=l.project_id and p.status='active')
                and (exists(select 1 from github_import_settings s where s.link_id=l.id and s.enabled and s.baseline_ready)
                or exists(select 1 from github_task_sources s where s.link_id=l.id and s.issue_id=?))))
                on conflict(link_id,kind,stage) where kind in ('apply','baseline','mapping') and state in ('queued','running')
                do update set requested_again=true,generation=excluded.generation,updated_at=excluded.updated_at
                """,UUID.randomUUID(),kind,stage,UUID.randomUUID(),ts(now),ts(now),ts(now),link,kind,stage.startsWith("issue:")?Long.parseLong(stage.substring(6)):0L);
    }
    @Override public void reconcileCached(Work work, Instant now) {
        workspaces.lockByOwnerId(work.user()).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        projects.lockOwned(work.workspace(), work.project()).orElseThrow(() -> new GitHubException("RESOURCE_NOT_FOUND"));
        jdbc.queryForList("select user_id from github_accounts where user_id=? for update", work.user());
        var link = jdbc.queryForMap("select generation,status from github_repository_links where id=? for no key update", work.link());
        if (ownedClaim(work, now) == null) return;
        if (number(link,"generation") != work.generation() || Set.of("disconnecting","disconnected").contains(link.get("status"))) {
            finishAttempt(work,"queued",work.stage(),work.page(),"GITHUB_SOURCE_CHANGED",now,now); return;
        }
        requireAccess(work.link(), work.user(), now);
        var rows = jdbc.queryForList("select issue_number,collected_at from github_issue_snapshots where link_id=? order by issue_id limit 50 offset ?",
                work.link(), ((long)work.page() - 1) * 50);
        for (var row : rows) if (((Timestamp)row.get("collected_at")).toInstant().isBefore(work.createdAt()))
            enqueueIssue(work.link(), number(row,"issue_number"), now);
        if (rows.size() == 50) finishAttempt(work,"queued","verify",work.page()+1,null,now,now);
        else apply(work, new GitHubSourceClient.Page(List.of(),List.of(),false), now);
    }
    private Map<String,Object> ownedClaim(Work work, Instant now) {
        var rows = jdbc.queryForList("select * from github_sync_jobs where id=? and claim=? and state='running' and lease_until>? for update", work.id(), work.nonce(), ts(now));
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private void finishAttempt(Work work, String state, String stage, int page, String error, Instant available, Instant now) {
        jdbc.update("update github_sync_jobs set state=?,stage=?,page=?,error_code=?,attempts=?,available_at=?,claim=null,lease_until=null,updated_at=? where id=? and claim=?",
                state, stage, page, error, error == null ? 0 : work.attempts(), ts(available), ts(now), work.id(), work.nonce());
        jdbc.update("update github_dispatch_limits set claim=null,lease_until=null where installation_id=? and claim=?", work.installation(), work.nonce());
    }
    @Override public void dispatchInbox(Instant now) {
        jdbc.update("""
                update github_webhook_inbox set payload=null where delivery_id in
                (select delivery_id from github_webhook_inbox where payload is not null and
                ((state='completed' and completed_at<?) or (state='failed' and received_at<?)) order by received_at limit 50)
                """, ts(now.minusSeconds(7 * 86400)), ts(now.minusSeconds(30 * 86400)));
        var rows = jdbc.queryForList("select * from github_webhook_inbox where state='queued' and available_at<=? order by received_at,delivery_id for update skip locked limit 1", ts(now));
        if (rows.isEmpty()) return;
        var event = rows.getFirst(); UUID delivery = (UUID)event.get("delivery_id");
        var links = event.get("installation_id") == null ? List.<Map<String,Object>>of() : jdbc.queryForList("""
                select id from github_repository_links where installation_id=? and (?::bigint is null or repository_id=?)
                and status not in ('disconnecting','disconnected') and (?::uuid is null or id>?) order by id limit 50
                """, event.get("installation_id"), event.get("repository_id"), event.get("repository_id"), event.get("after_link"), event.get("after_link"));
        for (var link : links) {
            if ("issues".equals(event.get("event_type")) && event.get("issue_number") != null) enqueueIssue((UUID)link.get("id"), number(event,"issue_number"), now);
            else enqueue((UUID)link.get("id"), now);
        }
        if (links.size() == 50) jdbc.update("update github_webhook_inbox set after_link=? where delivery_id=?", links.getLast().get("id"), delivery);
        else jdbc.update("update github_webhook_inbox set state='completed',completed_at=? where delivery_id=?", ts(now), delivery);
    }
    @Override @Transactional(readOnly = true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) public Job job(UUID user, UUID id, Instant now) {
        var rows = jdbc.queryForList("select j.* from github_sync_jobs j join github_repository_links l on l.id=j.link_id where j.id=? and l.user_id=?", id, user);
        if (rows.isEmpty()) throw new GitHubException("RESOURCE_NOT_FOUND");
        var row = rows.getFirst(); if(!metadataOnlyJob(user,id))requireAccess((UUID)row.get("link_id"), user, now);
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from github_task_job_items x join github_issue_snapshots i on i.link_id=? and i.issue_id=x.issue_id where x.job_id=? and not i.available)",Boolean.class,row.get("link_id"),id)))throw new GitHubException("RESOURCE_NOT_FOUND");
        return new Job(id, (UUID)row.get("link_id"), (String)row.get("state"), (String)row.get("stage"), ((Number)row.get("page")).intValue(),
                ((Number)row.get("attempts")).intValue(), (String)row.get("error_code"), ((Timestamp)row.get("created_at")).toInstant(), ((Timestamp)row.get("updated_at")).toInstant(),
                ((Number)row.get("completed_count")).intValue(),((Number)row.get("failed_count")).intValue(),
                jdbc.queryForList("select x.* from github_task_job_items x left join github_issue_snapshots i on i.link_id=? and i.issue_id=x.issue_id where x.job_id=? and (i.available or i.issue_id is null) order by x.issue_id limit 100",row.get("link_id"),id).stream()
                    .map(item->new Item(number(item,"issue_id"),(String)item.get("state"),(UUID)item.get("task_id"),(String)item.get("error_code"))).toList(),(String)row.get("kind"));
    }
    @Override @Transactional(readOnly = true) public UUID jobLink(UUID user, UUID id) {
        var rows = jdbc.queryForList("select j.link_id from github_sync_jobs j join github_repository_links l on l.id=j.link_id where j.id=? and l.user_id=?", id, user);
        if (rows.isEmpty()) throw new GitHubException("RESOURCE_NOT_FOUND");
        return (UUID)rows.getFirst().get("link_id");
    }
    @Override @Transactional(readOnly=true) public boolean metadataOnlyJob(UUID user,UUID id){
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from github_sync_jobs j join github_repository_links l on l.id=j.link_id join users u on u.id=l.user_id where j.id=? and l.user_id=? and u.disabled_at is null and j.kind='detach')",Boolean.class,id,user));
    }
    private void requireAccess(UUID link, UUID user, Instant now) {
        Integer count = jdbc.queryForObject("""
                select count(*) from github_repository_links l join github_accounts a on a.user_id=l.user_id
                where l.id=? and l.user_id=? and a.status='connected' and a.github_user_id=l.github_user_id
                and l.access_status='verified' and l.access_account_version=a.token_version and l.access_expires_at>?
                and l.status not in ('disconnecting','disconnected')
                """, Integer.class, link, user, ts(now));
        if (count == null || count != 1) throw new GitHubException("GITHUB_ACCESS_DENIED");
    }
    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private static Timestamp ts(Instant now) { return Timestamp.from(now); }
}
