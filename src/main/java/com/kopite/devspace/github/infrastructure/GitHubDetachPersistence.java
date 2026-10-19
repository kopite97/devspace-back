package com.kopite.devspace.github.infrastructure;
import com.kopite.devspace.github.application.port.GitHubDetachStore;
import com.kopite.devspace.github.domain.GitHubException;
import com.kopite.devspace.task.application.command.TaskSourceCommands;
import com.kopite.devspace.task.application.port.TaskSourceAccess;
import com.kopite.devspace.task.domain.TaskRepository;
import com.kopite.devspace.project.domain.ProjectRepository;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

@Repository @Transactional
public class GitHubDetachPersistence implements GitHubDetachStore {
    private final JdbcTemplate jdbc;private final EntityManager em;private final JsonMapper json;
    private final PersonalWorkspaceRepository workspaces;private final ProjectRepository projects;private final TaskRepository tasks;
    private final TaskSourceCommands commands;private final TaskSourceAccess access;
    public GitHubDetachPersistence(JdbcTemplate jdbc,EntityManager em,JsonMapper json,PersonalWorkspaceRepository workspaces,ProjectRepository projects,TaskRepository tasks,TaskSourceCommands commands,TaskSourceAccess access){this.jdbc=jdbc;this.em=em;this.json=json;this.workspaces=workspaces;this.projects=projects;this.tasks=tasks;this.commands=commands;this.access=access;}
    @Override public UUID request(UUID user,UUID project,UUID link,long revision,String disposition,Instant now){
        if(!Set.of("convert","purge").contains(disposition==null?"":disposition))throw new GitHubException("VALIDATION_ERROR");
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        projects.lockOwned(workspace.getId(),project).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        var links=jdbc.queryForList("select * from github_repository_links where id=? and user_id=? and project_id=? for update",link,user,project);if(links.isEmpty())throw new GitHubException("RESOURCE_NOT_FOUND");
        var row=links.getFirst();if(num(row,"revision")!=revision||revision>=9007199254740991L)throw new GitHubException("REVISION_CONFLICT");
        if(row.get("status").equals("disconnected"))throw new GitHubException("GITHUB_LINK_INACTIVE");
        jdbc.update("update github_sync_jobs set state='failed',error_code='GITHUB_LINK_INACTIVE',claim=null,lease_until=null where link_id=? and state in ('queued','running','partial')",link);
        jdbc.update("update github_repository_links set status='disconnecting',generation=generation+1,revision=revision+1,visibility_version=visibility_version+1,updated_at=? where id=?",ts(now),link);
        UUID job=UUID.randomUUID();jdbc.update("insert into github_sync_jobs(id,link_id,kind,stage,scan_id,generation,available_at,created_at,updated_at,payload) select ?,id,'detach','0',?,generation,?,?,?,?::jsonb from github_repository_links where id=?",job,UUID.randomUUID(),ts(now),ts(now),ts(now),json.writeValueAsString(Map.of("disposition",disposition)),link);
        workspace.recordBusinessMutation();return job;
    }
    @Override @Transactional(readOnly=true) public UUID taskLink(UUID user,UUID task){
        var rows=jdbc.queryForList("select s.link_id from github_task_sources s join workspaces w on w.id=s.workspace_id where s.task_id=? and w.owner_user_id=?",task,user);
        if(rows.isEmpty())throw new GitHubException("RESOURCE_NOT_FOUND");return(UUID)rows.getFirst().get("link_id");
    }
    @Override public void convert(UUID user,UUID id,long revision,Instant now){
        var workspace=workspaces.lockByOwnerId(user).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        var discovered=tasks.findOwned(workspace.getId(),id).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        projects.lockOwned(workspace.getId(),discovered.getProjectId()).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        tasks.lockOwned(workspace.getId(),id).orElseThrow();
        jdbc.queryForList("select user_id from github_accounts where user_id=? for update",user);
        var source=access.requireAccessible(workspace.getId(),id);if(source==null)throw new GitHubException("RESOURCE_NOT_FOUND");
        exclude(id,"converted",now);commands.convert(user,id,revision,now);jdbc.update("delete from github_task_sources where task_id=?",id);
    }
    @Override @Transactional(readOnly=true) public Optional<Work> next(Instant now){
        return jdbc.queryForList("select j.id,j.link_id,j.generation,j.payload,l.user_id,l.installation_id,l.repository_id from github_sync_jobs j join github_repository_links l on l.id=j.link_id where j.kind='detach' and j.state='queued' and j.available_at<=? order by j.created_at,j.id limit 1",ts(now)).stream().map(r->new Work((UUID)r.get("id"),(UUID)r.get("link_id"),(UUID)r.get("user_id"),num(r,"installation_id"),num(r,"repository_id"),num(r,"generation"),json.readTree(r.get("payload").toString()).path("disposition").asString())).findFirst();
    }
    @Override public void apply(Work work,Proof proof,Instant now){
        var workspace=workspaces.lockByOwnerId(work.user()).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        var link=jdbc.queryForMap("select * from github_repository_links where id=?",work.link());
        projects.lockOwned(workspace.getId(),(UUID)link.get("project_id")).orElseThrow(()->new GitHubException("RESOURCE_NOT_FOUND"));
        var rows=jdbc.queryForList("select s.task_id,i.available from github_task_sources s join github_issue_snapshots i on i.link_id=s.link_id and i.issue_id=s.issue_id where s.link_id=? order by s.task_id limit 50",work.link());
        for(var row:rows)tasks.lockOwned(workspace.getId(),(UUID)row.get("task_id")).orElseThrow();
        var accounts=jdbc.queryForList("select * from github_accounts where user_id=? for update",work.user());
        var epoch=jdbc.queryForList("select generation from github_installation_epochs where installation_id=? for update",work.installation());
        jdbc.queryForList("select id from github_repository_links where id=? for update",work.link());
        var jobs=jdbc.queryForList("select * from github_sync_jobs where id=? and state='queued' for update",work.id());if(jobs.isEmpty())return;
        if(num(link,"generation")!=work.generation()){stop(work,"GITHUB_SOURCE_CHANGED",now);return;}
        if(work.disposition().equals("convert")){
            if(proof==null||!proof.expiresAt().isAfter(now)||accounts.isEmpty()||!accounts.getFirst().get("status").equals("connected")||num(accounts.getFirst(),"token_version")!=proof.accountVersion()||epoch.isEmpty()||num(epoch.getFirst(),"generation")!=proof.installationVersion())throw new GitHubException("GITHUB_ACCESS_DENIED");
        }
        int completed=0;boolean blocked=false;
        for(var row:rows){
            UUID id=(UUID)row.get("task_id");
            if(work.disposition().equals("convert")&&!(boolean)row.get("available")){blocked=true;continue;}
            exclude(id,work.disposition().equals("convert")?"converted":"purged",now);
            if(work.disposition().equals("convert")){var task=tasks.findOwned(workspace.getId(),id).orElseThrow();commands.convert(work.user(),id,task.getRevision(),now);jdbc.update("delete from github_task_sources where task_id=?",id);}
            else{jdbc.update("delete from github_task_sources where task_id=?",id);commands.purge(work.user(),id);em.flush();}
            completed++;
        }
        jdbc.update("update github_sync_jobs set completed_count=completed_count+?,updated_at=? where id=?",completed,ts(now),work.id());
        if(blocked){stop(work,"GITHUB_ISSUE_UNAVAILABLE",now);return;}
        if(rows.size()<50){
            // Cache cleanup is itself bounded; retained exclusions prevent automatic re-creation after reconnect.
            int removed=jdbc.update("delete from github_issue_snapshots where (link_id,issue_id) in (select link_id,issue_id from github_issue_snapshots where link_id=? order by issue_id limit 50)",work.link());
            if(removed==50)return;
            int labels=jdbc.update("delete from github_label_snapshots where (link_id,label_id) in (select link_id,label_id from github_label_snapshots where link_id=? order by label_id limit 50)",work.link());
            if(labels==50)return;
            jdbc.update("update github_repository_links set status='disconnected',full_name='',html_url='',access_status='denied',access_expires_at=?,revision=revision+1,updated_at=? where id=?",ts(now),ts(now),work.link());
            jdbc.update("update github_sync_jobs set state='completed',updated_at=? where id=?",ts(now),work.id());workspace.recordBusinessMutation();
        }
    }
    private void exclude(UUID task,String reason,Instant now){jdbc.update("insert into github_issue_exclusions(workspace_id,project_id,issue_id,task_id,reason,created_at) select workspace_id,project_id,issue_id,task_id,?,? from github_task_sources where task_id=? on conflict(workspace_id,project_id,issue_id) do update set task_id=excluded.task_id,reason=excluded.reason,created_at=excluded.created_at",reason,ts(now),task);}
    @Override public void stop(Work work,String error,Instant now){jdbc.update("update github_sync_jobs set state='partial',error_code=?,updated_at=? where id=? and state='queued'",error,ts(now),work.id());}
    private static long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Timestamp ts(Instant instant){return Timestamp.from(instant);}
}
