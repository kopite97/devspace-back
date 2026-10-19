package com.kopite.devspace.overview.presentation.dto;
import com.kopite.devspace.overview.application.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
public record OverviewResponse(
    @Schema(description="all, uncategorized, or an owned Category UUID") String category,
    @Schema(types={"string","null"},format="uuid") UUID projectId,
    OverviewProjectCounts projects,OverviewTaskCounts tasks,
    @Schema(description="Server observation time within the read snapshot, not a commit watermark") Instant asOf, boolean externalDataUnavailable) {
    public static OverviewResponse from(OverviewSnapshot s) {
        var buckets=s.projects().stream().map(c -> new OverviewProjectCategoryCounts(c.categoryId(),c.active(),c.archived())).toList();
        var t=s.tasks();
        return new OverviewResponse(s.filter().category(),s.filter().projectId(),
            new OverviewProjectCounts(buckets.stream().mapToLong(OverviewProjectCategoryCounts::total).sum(),
                buckets.stream().mapToLong(OverviewProjectCategoryCounts::archived).sum(),buckets),
            new OverviewTaskCounts(t.counts().get("todo"),t.counts().get("doing"),t.counts().get("done"),t.total()),t.asOf(),t.externalDataUnavailable());
    }
}
