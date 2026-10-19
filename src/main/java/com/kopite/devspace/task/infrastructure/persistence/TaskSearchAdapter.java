package com.kopite.devspace.task.infrastructure.persistence;

import com.kopite.devspace.task.application.model.*;
import com.kopite.devspace.task.application.port.TaskSearchRepository;
import com.kopite.devspace.task.application.query.TaskListFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** The same SQL visibility view protects details, pages and all aggregate queries. */
@Repository
public class TaskSearchAdapter implements TaskSearchRepository {
    private static final String FROM = " from tasks t join projects p on p.id=t.project_id and p.workspace_id=t.workspace_id left join github_visible_task_sources s on s.task_id=t.id";
    private static final String ACCESS = " and (t.source_type='local' or (:githubEnabled and s.task_id is not null))";
    private static final String SELECT = "select t.*,p.name as project_name,p.category_id,s.link_id,s.issue_id,s.issue_number,s.repository_name,s.html_url,s.status_reason,s.conflict_label_ids,s.content_error,s.source_state,s.source_state_reason,s.paused,s.source_updated_at,s.last_applied_at";
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final boolean enabled;
    public TaskSearchAdapter(JdbcTemplate jdbc, JsonMapper json, @Value("${app.github.enabled:false}") boolean enabled) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc); this.json = json; this.enabled = enabled;
    }
    @Override public Optional<TaskSnapshot> findOwned(UUID workspace, UUID id) {
        return jdbc.queryForList(SELECT + FROM + " where t.workspace_id=:workspace and t.id=:id" + ACCESS,
                new MapSqlParameterSource().addValue("workspace",workspace).addValue("id",id).addValue("githubEnabled",enabled)).stream().map(this::snapshot).findFirst();
    }
    @Override public long count(UUID workspace, TaskListFilter filter) { return jdbc.queryForObject("select count(*)" + FROM + where(filter), params(workspace,filter), Long.class); }
    @Override public List<TaskSnapshot> page(UUID workspace, TaskListFilter filter, TaskCursor after) {
        var args = params(workspace,filter).addValue("limit",filter.limit()+1);
        String seek = "";
        if (after != null) {
            seek = " and (t.created_at<:createdAt or (t.created_at=:createdAt and t.id<:id))";
            args.addValue("createdAt",Timestamp.from(after.createdAt())).addValue("id",after.id());
        }
        return jdbc.queryForList(SELECT + FROM + where(filter) + seek + " order by t.created_at desc,t.id desc limit :limit",args).stream().map(this::snapshot).toList();
    }
    @Override public Map<String,Long> counts(UUID workspace, TaskListFilter filter) {
        Map<String,Long> result = new LinkedHashMap<>(); result.put("todo",0L); result.put("doing",0L); result.put("done",0L);
        jdbc.queryForList("select t.status,count(*) as count" + FROM + where(filter) + " group by t.status",params(workspace,filter))
                .forEach(row -> result.put((String)row.get("status"),((Number)row.get("count")).longValue()));
        return result;
    }
    private String where(TaskListFilter f) {
        return " where t.workspace_id=:workspace" + ACCESS
                + (f.projectId()==null ? "" : " and t.project_id=:projectId")
                + ("all".equals(f.category()) ? "" : "uncategorized".equals(f.category()) ? " and p.category_id is null" : " and p.category_id=:categoryId")
                + ("all".equals(f.projectStatus()) ? "" : " and p.status=:projectStatus")
                + (f.status()==null ? "" : " and t.status=:status")
                + (f.deleted() ? " and t.deleted_at is not null" : " and t.deleted_at is null")
                + (f.sourceReason()==null ? "" : f.sourceReason().equals("content_limit_exceeded") ? " and s.content_error='CONTENT_LIMIT_EXCEEDED'" : " and s.status_reason=:sourceReason")
                + " and (lower(t.title) like lower(:pattern) escape '!' or lower(p.name) like lower(:pattern) escape '!')";
    }
    private MapSqlParameterSource params(UUID workspace, TaskListFilter f) {
        return new MapSqlParameterSource().addValue("workspace",workspace).addValue("githubEnabled",enabled).addValue("projectId",f.projectId())
                .addValue("categoryId",com.kopite.devspace.projectcategory.application.CategoryFilter.id(f.category())).addValue("projectStatus",f.projectStatus())
                .addValue("status",f.status()).addValue("sourceReason",f.sourceReason())
                .addValue("pattern","%"+f.query().replace("!","!!").replace("%","!%").replace("_","!_")+"%");
    }
    private TaskSnapshot snapshot(Map<String,Object> row) {
        TaskSourceSnapshot source = null;
        if (row.get("link_id") != null && "github".equals(row.get("source_type"))) {
            List<Long> conflicts = new ArrayList<>(); for (var node : json.readTree(row.get("conflict_label_ids").toString())) conflicts.add(node.asLong());
            source = new TaskSourceSnapshot("github",(UUID)row.get("link_id"),number(row,"issue_id"),number(row,"issue_number"),(String)row.get("repository_name"),
                    (String)row.get("html_url"),(String)row.get("status_reason"),List.copyOf(conflicts),(String)row.get("content_error"),(String)row.get("source_state"),
                    (String)row.get("source_state_reason"),(boolean)row.get("paused"),instant(row,"source_updated_at"),instant(row,"last_applied_at"),List.of("priority","tag"));
        }
        return new TaskSnapshot((UUID)row.get("id"),number(row,"revision"),instant(row,"created_at"),instant(row,"updated_at"),(String)row.get("title"),
                (UUID)row.get("project_id"),(String)row.get("project_name"),(UUID)row.get("category_id"),(String)row.get("description"),(String)row.get("status"),
                (String)row.get("priority"),(String)row.get("tag"),instant(row,"deleted_at"),null,source);
    }
    private static long number(Map<String,Object> row,String field) { return ((Number)row.get(field)).longValue(); }
    private static Instant instant(Map<String,Object> row,String field) { return row.get(field)==null ? null : ((Timestamp)row.get(field)).toInstant(); }
}
