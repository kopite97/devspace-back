package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.task.application.port.TaskSourceAccess;
import com.kopite.devspace.task.application.model.TaskSourceSnapshot;
import com.kopite.devspace.task.application.exception.TaskNotFoundException;
import com.kopite.devspace.github.application.GitHubAccessService;
import com.kopite.devspace.github.application.port.GitHubSyncStore;
import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Repository
public class GitHubTaskSourceAccess implements TaskSourceAccess {
    private final JdbcTemplate jdbc;
    private final GitHubAccessService access;
    private final GitHubSyncStore sync;
    private final JsonMapper json;
    private final Clock clock;
    private final boolean enabled;
    public GitHubTaskSourceAccess(JdbcTemplate jdbc,GitHubAccessService access,GitHubSyncStore sync,JsonMapper json,Clock projectClock,@Value("${app.github.enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.access=access; this.sync=sync; this.json=json; this.clock=projectClock; this.enabled=enabled;
    }
    @Override @Transactional(propagation=Propagation.NEVER) public void prepare(UUID user) {
        if (!enabled) return;
        var rows=jdbc.queryForList("""
                select l.id from github_repository_links l join github_accounts a on a.user_id=l.user_id
                where l.user_id=? and l.status not in ('disconnecting','disconnected')
                and (l.access_status<>'verified' or l.access_expires_at is null or l.access_expires_at<=? or l.access_account_version<>a.token_version or a.status<>'connected')
                order by l.access_expires_at nulls first,l.id limit 1
                """,user,Timestamp.from(clock.instant()));
        for(var row:rows) try { access.refresh(user,(UUID)row.get("id")); } catch(GitHubException ignored) { /* ordinary Tasks remain available */ }
    }
    @Override @Transactional(readOnly=true) public TaskSourceSnapshot requireAccessible(UUID workspace,UUID task) {
        var local=jdbc.queryForList("select source_type from tasks where workspace_id=? and id=?",workspace,task);
        if(local.isEmpty()) throw new TaskNotFoundException();
        if("local".equals(local.getFirst().get("source_type"))) return null;
        if(!enabled) throw new TaskNotFoundException();
        var rows=jdbc.queryForList("select * from github_visible_task_sources where workspace_id=? and task_id=?",workspace,task);
        if(rows.isEmpty()) throw new TaskNotFoundException();
        var r=rows.getFirst(); List<Long> conflicts=new ArrayList<>(); for(var id:json.readTree(r.get("conflict_label_ids").toString())) conflicts.add(id.asLong());
        return new TaskSourceSnapshot("github",(UUID)r.get("link_id"),((Number)r.get("issue_id")).longValue(),((Number)r.get("issue_number")).longValue(),
                (String)r.get("repository_name"),(String)r.get("html_url"),(String)r.get("status_reason"),List.copyOf(conflicts),(String)r.get("content_error"),
                (String)r.get("source_state"),(String)r.get("source_state_reason"),(boolean)r.get("paused"),((Timestamp)r.get("source_updated_at")).toInstant(),
                ((Timestamp)r.get("last_applied_at")).toInstant(),List.of("priority","tag"));
    }
    @Override @Transactional(readOnly=true) public String scopeVersion(UUID workspace) {
        String value=jdbc.queryForObject("""
                select coalesce(string_agg(l.id::text||':'||l.generation||':'||l.visibility_version||':'||a.token_version||':'||a.access_version||':'||
                (a.status='connected' and l.access_status='verified' and l.access_account_version=a.token_version and coalesce(l.access_expires_at>CURRENT_TIMESTAMP,false))::text,',' order by l.id),'none')
                from github_repository_links l join github_accounts a on a.user_id=l.user_id where l.workspace_id=? and l.status<>'disconnected'
                """,String.class,workspace);
        if("none".equals(value)) return value;
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((enabled+":"+value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    @Override @Transactional(readOnly=true) public boolean unavailable(UUID workspace) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tasks t where t.workspace_id=? and t.source_type='github' and (not ? or not exists(select 1 from github_visible_task_sources s where s.task_id=t.id)))",Boolean.class,workspace,enabled));
    }
    @Override @Transactional public void deleted(UUID workspace,UUID task) {
        jdbc.update("""
                insert into github_issue_exclusions(workspace_id,project_id,issue_id,task_id,reason,created_at)
                select workspace_id,project_id,issue_id,task_id,'deleted',? from github_task_sources where workspace_id=? and task_id=?
                on conflict(workspace_id,project_id,issue_id) do update set task_id=excluded.task_id,reason='deleted',created_at=excluded.created_at
                """,Timestamp.from(clock.instant()),workspace,task);
    }
    @Override @Transactional public void restored(UUID workspace,UUID task) {
        var rows=jdbc.queryForList("select link_id,issue_number from github_task_sources where workspace_id=? and task_id=?",workspace,task);
        for(var row:rows) sync.enqueueIssue((UUID)row.get("link_id"),((Number)row.get("issue_number")).longValue(),clock.instant());
    }
}
