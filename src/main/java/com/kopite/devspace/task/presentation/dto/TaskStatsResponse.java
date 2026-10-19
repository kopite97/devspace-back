package com.kopite.devspace.task.presentation.dto;
import com.kopite.devspace.task.application.query.TaskQueryService;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
public record TaskStatsResponse(Counts counts,long total,Instant asOf,boolean externalDataUnavailable) {
    @Schema(name="TaskStatusCounts")
    public record Counts(long todo,long doing,long done) {}
    public static TaskStatsResponse from(TaskQueryService.Stats stats) {
        return new TaskStatsResponse(new Counts(stats.counts().get("todo"),stats.counts().get("doing"),
            stats.counts().get("done")),stats.total(),stats.asOf(),stats.externalDataUnavailable());
    }
}
