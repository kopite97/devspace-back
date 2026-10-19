package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.GitHubTaskSettingsStore;
import com.kopite.devspace.github.domain.*;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import com.kopite.devspace.project.domain.ProjectRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository @Transactional
public class GitHubTaskSettingsPersistence implements GitHubTaskSettingsStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final PersonalWorkspaceRepository workspaces;
    private final ProjectRepository projects;
    public GitHubTaskSettingsPersistence(JdbcTemplate jdbc,JsonMapper json,PersonalWorkspaceRepository workspaces,ProjectRepository projects){this.jdbc=jdbc;this.json=json;this.workspaces=workspaces;this.projects=projects;}
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Settings get(UUID user,UUID link,Instant now){requireAccess(user,link,now);return settings(link);}
    @Override public Settings configure(UUID user,UUID link,long revision,boolean enabled,GitHubImportCriteria criteria,Instant now){
        lock(user,link,revision,now); validateLabels(link,criteria.labelIds());
        jdbc.update("update github_import_settings set enabled=?,generation=generation+1,baseline_ready=false,label_ids=?::jsonb,assignee_ids=?::jsonb where link_id=?",enabled,json.writeValueAsString(criteria.labelIds()),json.writeValueAsString(criteria.assigneeIds()),link);
        enqueue(link,"baseline",now);return settings(link);
    }
    @Override public Settings mapping(UUID user,UUID link,long revision,GitHubTaskMapping mapping,Instant now){
        lock(user,link,revision,now);validateLabels(link,java.util.stream.Stream.of(mapping.todoLabelId(),mapping.doingLabelId(),mapping.doneLabelId()).filter(Objects::nonNull).toList());
        jdbc.update("update github_import_settings set todo_label_id=?,doing_label_id=?,done_label_id=? where link_id=?",mapping.todoLabelId(),mapping.doingLabelId(),mapping.doneLabelId(),link);
        enqueue(link,"mapping",now);return settings(link);
    }
    @Override public Settings pause(UUID user,UUID link,long revision,boolean paused,Instant now){
        lock(user,link,revision,now);
        jdbc.update("update github_repository_links set paused=?,generation=generation+1 where id=?",paused,link);
        jdbc.update("update github_import_settings set generation=generation+1,baseline_ready=false where link_id=?",link);
        if(!paused){
            jdbc.update("update github_sync_jobs set generation=(select generation from github_repository_links where id=?),payload='{}',requested_again=false where link_id=? and kind in ('baseline','mapping') and state='queued'",link,link);
            enqueue(link,"baseline",now);enqueue(link,"mapping",now);
        }
        return settings(link);
    }
    private void lock(UUID user,UUID link,long revision,Instant now){
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        var row=jdbc.queryForList("select project_id from github_repository_links where id=? and user_id=?",link,user);
        if(row.isEmpty())throw new GitHubException("RESOURCE_NOT_FOUND");
        var project=projects.lockOwned(workspace.getId(),(UUID)row.getFirst().get("project_id")).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        if(!project.getStatus().equals("active"))throw new GitHubException("PROJECT_ARCHIVED");
        jdbc.queryForList("select user_id from github_accounts where user_id=? for update",user);
        jdbc.queryForList("select id from github_repository_links where id=? for update",link);
        requireAccess(user,link,now);
        if(revision<1 || revision>=9007199254740991L || jdbc.update("update github_repository_links set revision=revision+1,updated_at=? where id=? and revision=?",Timestamp.from(now),link,revision)!=1)throw new GitHubException("REVISION_CONFLICT");
        workspace.recordBusinessMutation();
    }
    private void enqueue(UUID link,String kind,Instant now){
        // A changed configuration restarts the bounded database scan; no source HTTP is needed here.
        jdbc.update("""
            insert into github_sync_jobs(id,link_id,kind,stage,scan_id,generation,available_at,created_at,updated_at)
            select ?,id,?,'0',?,generation,?,?,? from github_repository_links where id=?
            on conflict(link_id,kind,stage) where kind in ('apply','baseline','mapping') and state in ('queued','running')
            do update set requested_again=true,updated_at=excluded.updated_at
            """,UUID.randomUUID(),kind,UUID.randomUUID(),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),link);
    }
    private void requireAccess(UUID user,UUID link,Instant now){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from github_repository_links l join github_accounts a on a.user_id=l.user_id join users u on u.id=l.user_id
            where l.id=? and l.user_id=? and u.disabled_at is null and l.status not in ('disconnecting','disconnected')
            and a.status='connected' and a.github_user_id=l.github_user_id and l.access_status='verified' and l.access_account_version=a.token_version and l.access_expires_at>?)
            """,Boolean.class,link,user,Timestamp.from(now))))throw new GitHubException("RESOURCE_NOT_FOUND");
    }
    private Settings settings(UUID link){
        var r=jdbc.queryForMap("select s.*,l.revision,l.paused from github_import_settings s join github_repository_links l on l.id=s.link_id where s.link_id=?",link);
        return new Settings(num(r,"revision"),(boolean)r.get("enabled"),num(r,"generation"),(boolean)r.get("baseline_ready"),new GitHubImportCriteria(ids(r.get("label_ids")),ids(r.get("assignee_ids"))),new GitHubTaskMapping(nullable(r,"todo_label_id"),nullable(r,"doing_label_id"),nullable(r,"done_label_id")),(boolean)r.get("paused"));
    }
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Preview preview(UUID user,UUID link,List<Long> selected,GitHubTaskMapping candidate,int limit,Long after,Instant now){
        if(limit<1||limit>100||after!=null&&after<0||selected!=null&&(selected.isEmpty()||selected.size()>100||selected.stream().anyMatch(id->id==null||id<=0)))throw new GitHubException("VALIDATION_ERROR");
        requireAccess(user,link,now);var config=settings(link);var mapping=candidate==null?config.mapping():candidate;
        if(candidate!=null)validateLabels(link,java.util.stream.Stream.of(candidate.todoLabelId(),candidate.doingLabelId(),candidate.doneLabelId()).filter(Objects::nonNull).toList());
        var available=new HashSet<>(jdbc.queryForList("select label_id from github_label_snapshots where link_id=? and available",Long.class,link));
        String from=" from github_issue_snapshots i join github_repository_links l on l.id=i.link_id left join github_task_sources s on s.link_id=i.link_id and s.issue_id=i.issue_id left join tasks t on t.id=s.task_id left join github_issue_exclusions e on e.workspace_id=l.workspace_id and e.project_id=l.project_id and e.issue_id=i.issue_id where i.link_id=:link and i.available";
        var args=new MapSqlParameterSource("link",link).addValue("limit",limit+1).addValue("after",after==null?0L:after);
        if(selected!=null){from+=" and i.issue_id in (:selected)";args.addValue("selected",selected);}
        else if(candidate==null){from+=" and i.label_ids @> cast(:labels as jsonb) and (cast(:assignees as jsonb)='[]'::jsonb or exists(select 1 from jsonb_array_elements(i.assignee_ids) a where cast(:assignees as jsonb) @> jsonb_build_array(a)))";args.addValue("labels",json.writeValueAsString(config.criteria().labelIds())).addValue("assignees",json.writeValueAsString(config.criteria().assigneeIds()));}
        else from+=" and s.task_id is not null";
        if(selected==null&&candidate==null&&!available.containsAll(config.criteria().labelIds()))from+=" and false";
        var query=new NamedParameterJdbcTemplate(jdbc);long total=query.queryForObject("select count(*)"+from,args,Long.class);
        var rows=query.queryForList("select i.*,s.task_id,s.last_decision_label_id,t.status as task_status,t.deleted_at,e.reason as exclusion_reason"+from+" and i.issue_id>:after order by i.issue_id limit :limit",args);
        List<Candidate> items=new ArrayList<>();
        for(var r:rows.stream().limit(limit).toList()){
            var labels=ids(r.get("label_ids"));var d=mapping.decide((String)r.get("state"),(String)r.get("state_reason"),labels,available,(String)r.get("task_status"),nullable(r,"last_decision_label_id"));
            String error=r.get("deleted_at")!=null?"TASK_IN_TRASH":candidate==null&&r.get("task_id")!=null?"ALREADY_IMPORTED":(boolean)r.get("content_overflow")?"CONTENT_LIMIT_EXCEEDED":null;
            items.add(new Candidate(num(r,"issue_id"),num(r,"issue_number"),(UUID)r.get("task_id"),d.status(),d.reason(),d.conflicts(),error,available.containsAll(config.criteria().labelIds())&&config.criteria().matches(labels,ids(r.get("assignee_ids"))),r.get("exclusion_reason")!=null));
        }
        return new Preview(List.copyOf(items),total,rows.size()>limit?items.getLast().issueId():null,config.revision(),config.baselineReady());
    }
    private void validateLabels(UUID link,List<Long> ids){var available=new HashSet<>(jdbc.queryForList("select label_id from github_label_snapshots where link_id=? and available",Long.class,link));if(!available.containsAll(ids))throw new GitHubException("GITHUB_LABEL_UNAVAILABLE");}
    private List<Long> ids(Object value){List<Long> ids=new ArrayList<>();for(var node:json.readTree(value.toString()))ids.add(node.asLong());return List.copyOf(ids);}
    private static long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Long nullable(Map<String,Object> row,String key){return row.get(key)==null?null:num(row,key);}
}
