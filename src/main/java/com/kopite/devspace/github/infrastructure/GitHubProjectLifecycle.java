package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.project.application.port.ProjectExternalLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

@Repository @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class GitHubProjectLifecycle implements ProjectExternalLifecycle {
    private final JdbcTemplate jdbc;
    @Override public void statusChanged(UUID workspace,UUID project,String previous,String current,Instant now){
        if(previous.equals(current))return;
        jdbc.update("update github_repository_links set generation=generation+1,revision=revision+1,updated_at=? where workspace_id=? and project_id=? and status not in ('disconnecting','disconnected')",Timestamp.from(now),workspace,project);
        jdbc.update("update github_import_settings set generation=generation+1,baseline_ready=false where link_id in (select id from github_repository_links where workspace_id=? and project_id=?)",workspace,project);
        if(current.equals("active"))for(UUID link:jdbc.queryForList("select id from github_repository_links where workspace_id=? and project_id=? and status not in ('disconnecting','disconnected')",UUID.class,workspace,project)){
            for(String kind:java.util.List.of("baseline","mapping"))jdbc.update("""
                insert into github_sync_jobs(id,link_id,kind,stage,scan_id,generation,available_at,created_at,updated_at)
                select ?,id,?,'0',?,generation,?,?,? from github_repository_links where id=?
                on conflict(link_id,kind,stage) where kind in ('apply','baseline','mapping') and state in ('queued','running')
                do update set generation=excluded.generation,payload='{}',requested_again=false,updated_at=excluded.updated_at
                """,UUID.randomUUID(),kind,UUID.randomUUID(),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),link);
        }
    }
}
