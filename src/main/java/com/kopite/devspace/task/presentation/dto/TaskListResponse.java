package com.kopite.devspace.task.presentation.dto;
import com.kopite.devspace.task.application.query.TaskQueryService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
public record TaskListResponse(List<TaskResponse> items,long total,
    @Schema(types={"string","null"}) String nextCursor, boolean externalDataUnavailable) {
    public static TaskListResponse from(TaskQueryService.Page page) {
        return new TaskListResponse(page.items().stream().map(TaskResponse::from).toList(),page.total(),page.nextCursor(),page.externalDataUnavailable());
    }
}
