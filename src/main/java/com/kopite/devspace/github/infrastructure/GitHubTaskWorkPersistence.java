package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.*;
import com.kopite.devspace.task.application.command.TaskSourceCommands;
import com.kopite.devspace.task.domain.TaskRepository;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import com.kopite.devspace.project.domain.ProjectRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Each local batch is atomic and contains no network I/O; all writes follow workspace/project/Task locks. */
@Repository @Transactional
public class GitHubTaskWorkPersistence implements GitHubTaskWorkStore {
    private final JdbcTemplate jdbc; private final JsonMapper json; private final EntityManager em;
    private final PersonalWorkspaceRepository workspaces;private final ProjectRepository projects;private final TaskRepository tasks;
    private final TaskSourceCommands commands;private final GitHubSyncStore sync;
    public GitHubTaskWorkPersistence(JdbcTemplate jdbc,JsonMapper json,EntityManager em,PersonalWorkspaceRepository workspaces,ProjectRepository projects,TaskRepository tasks,TaskSourceCommands commands,GitHubSyncStore sync){this.jdbc=jdbc;this.json=json;this.em=em;this.workspaces=workspaces;this.projects=projects;this.tasks=tasks;this.commands=commands;this.sync=sync;}
    @Override public UUID requestImport(UUID user,UUID link,long revision,List<Long> ids,String key,Instant now){
        if(ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(id->id==null||id<=0)||key==null||!key.matches("[!-~]{1,128}"))throw new GitHubException("VALIDATION_ERROR");
        ids=ids.stream().distinct().sorted().toList();
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        var rows=jdbc.queryForList("select * from github_repository_links where id=? and user_id=?",link,user);if(rows.isEmpty())throw new GitHubException("RESOURCE_NOT_FOUND");
        var row=rows.getFirst();var project=projects.lockOwned(workspace.getId(),(UUID)row.get("project_id")).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        jdbc.queryForList("select user_id from github_accounts where user_id=? for update",user);jdbc.queryForList("select id from github_repository_links where id=? for update",link);
        requireAccess(user,link,now);
        String hash=hash(revision+":"+json.writeValueAsString(ids));
        jdbc.update("delete from github_task_request_replays where user_id=? and expires_at<=?",user,ts(now));
        var replay=jdbc.queryForList("select request_hash,job_id from github_task_request_replays where user_id=? and link_id=? and operation='import' and request_key=?",user,link,key);
        if(!replay.isEmpty()){if(!hash.equals(replay.getFirst().get("request_hash")))throw new GitHubException("IDEMPOTENCY_KEY_REUSED");return(UUID)replay.getFirst().get("job_id");}
        if(!project.getStatus().equals("active"))throw new GitHubException("PROJECT_ARCHIVED");
        if((boolean)row.get("paused"))throw new GitHubException("GITHUB_SYNC_PAUSED");
        if(revision!=num(row,"revision"))throw new GitHubException("REVISION_CONFLICT");
        if(jdbc.queryForObject("select count(*) from github_sync_jobs where link_id=? and kind='import' and state='queued'",Integer.class,link)>=10)throw new GitHubException("GITHUB_IMPORT_QUEUE_FULL");
        UUID id=UUID.randomUUID();
        jdbc.update("insert into github_sync_jobs(id,link_id,kind,stage,scan_id,generation,available_at,created_at,updated_at,payload) values(?,?,'import','0',?,?,?,?,?,?::jsonb)",id,link,UUID.randomUUID(),num(row,"generation"),ts(now),ts(now),ts(now),json.writeValueAsString(Map.of("revision",revision)));
        for(long issue:ids){
            var source=jdbc.queryForList("select issue_number,collection_version from github_issue_snapshots where link_id=? and issue_id=? and available",link,issue);
            jdbc.update("insert into github_task_job_items(job_id,issue_id,state,error_code,required_collection_version) values(?,?,?,?,?)",id,issue,source.isEmpty()?"failed":"queued",source.isEmpty()?"GITHUB_ISSUE_UNAVAILABLE":null,source.isEmpty()?1:num(source.getFirst(),"collection_version")+1);
            if(!source.isEmpty())sync.enqueueIssue(link,num(source.getFirst(),"issue_number"),now);
        }
        jdbc.update("insert into github_task_request_replays(user_id,link_id,operation,request_key,request_hash,job_id,expires_at) values(?,?,'import',?,?,?,?)",user,link,key,hash,id,ts(now.plusSeconds(86400)));
        return id;
    }
    @Override @Transactional(readOnly=true) public Optional<Work> next(Instant now){
        return jdbc.queryForList("""
            select j.id,j.link_id,j.kind,l.user_id from github_sync_jobs j join github_repository_links l on l.id=j.link_id join projects p on p.id=l.project_id
            where j.kind in ('apply','baseline','mapping','import') and j.state='queued' and j.available_at<=? and l.status not in ('disconnecting','disconnected')
            and not l.paused and p.status='active' order by j.available_at,j.created_at,j.id limit 1
            """,ts(now)).stream().map(r->new Work((UUID)r.get("id"),(UUID)r.get("link_id"),(UUID)r.get("user_id"),(String)r.get("kind"))).findFirst();
    }
    @Override public void apply(Work work,Instant now){
        var workspace=workspaces.lockByOwnerId(work.user()).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        var link=jdbc.queryForMap("select * from github_repository_links where id=? and user_id=?",work.link(),work.user());
        var project=projects.lockOwned(workspace.getId(),(UUID)link.get("project_id")).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        var jobs=jdbc.queryForList("select * from github_sync_jobs where id=? and state='queued' and available_at<=?",work.id(),ts(now));if(jobs.isEmpty())return;
        var job=jobs.getFirst();String kind=(String)job.get("kind");var payload=json.readTree(job.get("payload").toString());
        if((boolean)link.get("paused")||!project.getStatus().equals("active"))return;
        if(Set.of("disconnecting","disconnected").contains(link.get("status")))return;
        // Lock affected Tasks before source/account/job rows. Workspace lock serializes list changes.
        List<Map<String,Object>> issues;
        if(kind.equals("import"))issues=jdbc.queryForList("select i.*,x.required_collection_version from github_task_job_items x join github_issue_snapshots i on i.link_id=? and i.issue_id=x.issue_id where x.job_id=? and x.state='queued' order by i.issue_id limit 50",work.link(),work.id());
        else if(kind.equals("apply"))issues=jdbc.queryForList("select * from github_issue_snapshots where link_id=? and issue_id=?",work.link(),Long.parseLong(((String)job.get("stage")).substring(6)));
        else issues=jdbc.queryForList("select * from github_issue_snapshots where link_id=? and issue_id>? order by issue_id limit 50",work.link(),payload.path("after").asLong(0));
        var sources=new HashMap<Long,Map<String,Object>>();
        for(var issue:issues){var rows=jdbc.queryForList("select * from github_task_sources where link_id=? and issue_id=?",work.link(),num(issue,"issue_id"));if(!rows.isEmpty())sources.put(num(issue,"issue_id"),rows.getFirst());}
        sources.values().stream().map(r->(UUID)r.get("task_id")).sorted().forEach(id->tasks.lockOwned(workspace.getId(),id).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND")));
        jdbc.queryForList("select user_id from github_accounts where user_id=? for update",work.user());
        jdbc.queryForList("select id from github_repository_links where id=? for update",work.link());
        jdbc.queryForList("select id from github_sync_jobs where id=? for update",work.id());
        requireAccess(work.user(),work.link(),now);
        if(num(job,"generation")!=num(link,"generation")||kind.equals("import")&&payload.path("revision").asLong()!=num(link,"revision")){
            if(kind.equals("baseline")||kind.equals("mapping")){
                jdbc.update("update github_sync_jobs set generation=?,payload='{}',updated_at=? where id=?",num(link,"generation"),ts(now),work.id());return;
            }
            if(kind.equals("import")){
                jdbc.update("update github_task_job_items set state='failed',error_code='GITHUB_SOURCE_CHANGED' where job_id=? and state='queued'",work.id());
                jdbc.update("update github_sync_jobs set state=case when completed_count>0 then 'partial' else 'failed' end,error_code='GITHUB_SOURCE_CHANGED',failed_count=(select count(*) from github_task_job_items where job_id=? and state='failed'),updated_at=? where id=?",work.id(),ts(now),work.id());return;
            }
            jdbc.update("update github_sync_jobs set state='failed',error_code='GITHUB_SOURCE_CHANGED',updated_at=? where id=?",ts(now),work.id());return;
        }
        var config=jdbc.queryForMap("select * from github_import_settings where link_id=?",work.link());
        var criteria=new GitHubImportCriteria(ids(config.get("label_ids")),ids(config.get("assignee_ids")));
        var mapping=new GitHubTaskMapping(nullable(config,"todo_label_id"),nullable(config,"doing_label_id"),nullable(config,"done_label_id"));
        var labels=new HashSet<>(jdbc.queryForList("select label_id from github_label_snapshots where link_id=? and available",Long.class,work.link()));
        boolean waiting=false;
        for(var issue:issues){
            long issueId=num(issue,"issue_id");var source=sources.get(issueId);
            if(kind.equals("import")&&num(issue,"collection_version")<num(issue,"required_collection_version")){
                if(instant(job,"created_at").plusSeconds(900).isBefore(now))item(work.id(),issueId,null,"GITHUB_SOURCE_NOT_FRESH");else waiting=true;
                continue;
            }
            boolean matched=labels.containsAll(criteria.labelIds())&&criteria.matches(ids(issue.get("label_ids")),ids(issue.get("assignee_ids")));
            var previous=jdbc.queryForList("select * from github_import_observations where link_id=? and issue_id=?",work.link(),issueId);
            boolean transition=previous.isEmpty()||num(previous.getFirst(),"generation")==num(config,"generation")&&!(boolean)previous.getFirst().get("matched");
            boolean retryContent=!previous.isEmpty()&&previous.getFirst().get("last_failure_hash")!=null&&!previous.getFirst().get("last_failure_hash").equals(issue.get("content_hash"));
            boolean excluded=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from github_issue_exclusions where workspace_id=? and project_id=? and issue_id=?)",Boolean.class,workspace.getId(),project.getId(),issueId));
            boolean automatic=kind.equals("apply")&&(boolean)config.get("enabled")&&(boolean)config.get("baseline_ready")&&matched&&(transition||retryContent)&&!excluded;
            if(!previous.isEmpty()&&Objects.equals(previous.getFirst().get("last_failure_hash"),issue.get("content_hash")))automatic=false;
            String failure=null;UUID taskId=source==null?null:(UUID)source.get("task_id");
            if(!kind.equals("baseline")&&(source!=null||kind.equals("import")||automatic)){
                if(!(boolean)issue.get("available"))failure="GITHUB_ISSUE_UNAVAILABLE";
                else if(kind.equals("import")&&source!=null)failure=tasks.findOwned(workspace.getId(),taskId).orElseThrow().getDeletedAt()!=null?"TASK_IN_TRASH":"ALREADY_IMPORTED";
                else if(source==null&&(boolean)issue.get("content_overflow"))failure="CONTENT_LIMIT_EXCEEDED";
                else taskId=applyIssue(work,workspace.getId(),project.getId(),issue,source,mapping,labels,now);
                if(kind.equals("import"))item(work.id(),issueId,taskId,failure);
                else if(failure!=null)jdbc.update("update github_sync_jobs set error_code=?,failed_count=failed_count+1 where id=?",failure,work.id());
            }
            if (!kind.equals("mapping")) jdbc.update("""
                insert into github_import_observations(link_id,issue_id,generation,matched,last_failure_hash) values(?,?,?,?,?)
                on conflict(link_id,issue_id) do update set generation=excluded.generation,matched=excluded.matched,last_failure_hash=excluded.last_failure_hash
                """,work.link(),issueId,num(config,"generation"),matched,"CONTENT_LIMIT_EXCEEDED".equals(failure)?issue.get("content_hash"):previous.isEmpty()?null:previous.getFirst().get("last_failure_hash"));
        }
        if(kind.equals("import")){
            long pending=jdbc.queryForObject("select count(*) from github_task_job_items where job_id=? and state='queued'",Long.class,work.id());
            jdbc.update("update github_sync_jobs set completed_count=(select count(*) from github_task_job_items where job_id=? and state='completed'),failed_count=(select count(*) from github_task_job_items where job_id=? and state='failed'),available_at=?,updated_at=? where id=?",work.id(),work.id(),ts(now.plusSeconds(waiting?5:0)),ts(now),work.id());
            if(pending==0)jdbc.update("update github_sync_jobs set state=case when failed_count=0 then 'completed' when completed_count=0 then 'failed' else 'partial' end where id=?",work.id());
        }else if(kind.equals("apply"))finish(work,now);
        else if(issues.size()==50){jdbc.update("update github_sync_jobs set payload=?::jsonb,updated_at=? where id=?",json.writeValueAsString(Map.of("after",num(issues.getLast(),"issue_id"))),ts(now),work.id());}
        else if((boolean)job.get("requested_again")){jdbc.update("update github_sync_jobs set payload='{}',requested_again=false,updated_at=? where id=?",ts(now),work.id());}
        else if(kind.equals("baseline")&&link.get("last_successful_sync_at")==null){
            jdbc.update("update github_sync_jobs set payload='{}',available_at=?,updated_at=? where id=?",ts(now.plusSeconds(5)),ts(now),work.id());
        }
        else{
            if(kind.equals("baseline"))jdbc.update("update github_import_settings set baseline_ready=true where link_id=?",work.link());
            finish(work,now);
        }
    }
    private UUID applyIssue(Work work,UUID workspace,UUID project,Map<String,Object> issue,Map<String,Object> source,GitHubTaskMapping mapping,Set<Long> labels,Instant now){
        var task=source==null?null:tasks.findOwned(workspace,(UUID)source.get("task_id")).orElseThrow();
        if(task!=null&&task.getDeletedAt()!=null)return task.getId();
        var decision=mapping.decide((String)issue.get("state"),(String)issue.get("state_reason"),ids(issue.get("label_ids")),labels,task==null?null:task.getStatus(),source==null?null:nullable(source,"last_decision_label_id"));
        boolean overflow=(boolean)issue.get("content_overflow");String error=overflow?"CONTENT_LIMIT_EXCEEDED":null;
        String conflicts=json.writeValueAsString(decision.conflicts());
        String title=overflow?task.getTitle():(String)issue.get("title"),body=overflow?task.getDescription():(String)issue.get("body");
        UUID id;
        if(task==null){id=commands.create(work.user(),project,title,body,decision.status(),now);em.flush();}
        else{
            id=task.getId();boolean metadata=!Objects.equals(source.get("status_reason"),decision.reason())||!ids(source.get("conflict_label_ids")).equals(decision.conflicts())||!Objects.equals(source.get("content_error"),error)||!Objects.equals(source.get("source_state"),issue.get("state"))||!Objects.equals(source.get("source_state_reason"),issue.get("state_reason"))||!Objects.equals(source.get("html_url"),issue.get("html_url"));
            commands.synchronize(work.user(),id,title,body,decision.status(),metadata,now);
        }
        jdbc.update("""
            insert into github_task_sources(task_id,workspace_id,project_id,link_id,issue_id,issue_number,html_url,last_decision_label_id,status_reason,conflict_label_ids,content_error,content_hash,source_state,source_state_reason,source_updated_at,last_applied_at)
            values(?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?) on conflict(task_id) do update set issue_number=excluded.issue_number,html_url=excluded.html_url,last_decision_label_id=excluded.last_decision_label_id,
            status_reason=excluded.status_reason,conflict_label_ids=excluded.conflict_label_ids,content_error=excluded.content_error,content_hash=excluded.content_hash,source_state=excluded.source_state,source_state_reason=excluded.source_state_reason,source_updated_at=excluded.source_updated_at,last_applied_at=excluded.last_applied_at
            """,id,workspace,project,work.link(),num(issue,"issue_id"),num(issue,"issue_number"),issue.get("html_url"),decision.decisionLabelId(),decision.reason(),conflicts,error,issue.get("content_hash"),issue.get("state"),issue.get("state_reason"),issue.get("source_updated_at"),ts(now));
        return id;
    }
    private void item(UUID job,long issue,UUID task,String error){jdbc.update("update github_task_job_items set state=?,task_id=?,error_code=? where job_id=? and issue_id=?",error==null?"completed":"failed",task,error,job,issue);}
    private void finish(Work work,Instant now){jdbc.update("update github_sync_jobs set state=case when failed_count>0 then 'failed' else 'completed' end,updated_at=? where id=?",ts(now),work.id());}
    @Override public void defer(Work work,String code,Instant retryAt,Instant now){
        Integer attempts=jdbc.queryForObject("select attempts from github_sync_jobs where id=?",Integer.class,work.id());
        Instant backoff=now.plusSeconds(Math.min(300,5L<<Math.min(attempts==null?0:attempts,6))+java.util.concurrent.ThreadLocalRandom.current().nextLong(5));
        if(backoff.isAfter(retryAt))retryAt=backoff;
        jdbc.update("update github_sync_jobs set attempts=attempts+1,state=case when attempts>=7 then 'failed' else 'queued' end,error_code=?,available_at=?,updated_at=? where id=? and state='queued'",code,ts(retryAt),ts(now),work.id());
    }
    private void requireAccess(UUID user,UUID link,Instant now){if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from github_repository_links l join github_accounts a on a.user_id=l.user_id join users u on u.id=l.user_id where l.id=? and l.user_id=? and a.status='connected' and a.github_user_id=l.github_user_id and u.disabled_at is null and l.access_status='verified' and l.access_account_version=a.token_version and l.access_expires_at>? and l.status not in ('disconnecting','disconnected'))",Boolean.class,link,user,ts(now))))throw new GitHubException("RESOURCE_NOT_FOUND");}
    private List<Long> ids(Object value){List<Long> ids=new ArrayList<>();for(var node:json.readTree(value.toString()))ids.add(node.asLong());return List.copyOf(ids);}
    private static long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Long nullable(Map<String,Object> row,String key){return row.get(key)==null?null:num(row,key);}
    private static Instant instant(Map<String,Object> row,String key){return ((Timestamp)row.get(key)).toInstant();}
    private static Timestamp ts(Instant now){return Timestamp.from(now);}
    private static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
