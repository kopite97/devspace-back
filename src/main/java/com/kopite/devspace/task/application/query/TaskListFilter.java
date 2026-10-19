package com.kopite.devspace.task.application.query;
import com.kopite.devspace.task.domain.TaskValidationException;
import java.util.UUID;
public record TaskListFilter(String category, UUID projectId, String projectStatus, String query,
                             String status, boolean deleted, int limit, String sourceReason, String accessScope) {
    public TaskListFilter(String category,UUID projectId,String projectStatus,String query,String status,boolean deleted,int limit) {
        this(category,projectId,projectStatus,query,status,deleted,limit,null,null);
    }
    public TaskListFilter withSourceReason(String reason) { return new TaskListFilter(category,projectId,projectStatus,query,status,deleted,limit,reason,accessScope); }
    public TaskListFilter withAccessScope(String scope) { return new TaskListFilter(category,projectId,projectStatus,query,status,deleted,limit,sourceReason,scope); }
    public TaskListFilter {
        category = com.kopite.devspace.projectcategory.application.CategoryFilter.normalize(category);
        if (!"all".equals(projectStatus) && !"active".equals(projectStatus) && !"archived".equals(projectStatus)) invalid("projectStatus");
        if (status != null && !"todo".equals(status) && !"doing".equals(status) && !"done".equals(status)) invalid("status");
        if (query == null) invalid("query");
        if (limit < 1 || limit > 100) invalid("limit");
        if (sourceReason != null && !java.util.Set.of("mapping_conflict","not_planned","unknown_closure","mapping_error","content_limit_exceeded").contains(sourceReason)) invalid("sourceReason");
    }
    private static void invalid(String field) { throw new TaskValidationException(field, "invalid filter value"); }
}
