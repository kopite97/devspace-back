package com.kopite.devspace.task.presentation.dto;
import com.kopite.devspace.task.application.model.TaskSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
public record TaskResponse(
    @Schema(accessMode=Schema.AccessMode.READ_ONLY) UUID id,
    @Schema(accessMode=Schema.AccessMode.READ_ONLY,minimum="1",maximum="9007199254740991") long revision,
    @Schema(accessMode=Schema.AccessMode.READ_ONLY) Instant createdAt,
    @Schema(accessMode=Schema.AccessMode.READ_ONLY) Instant updatedAt,
    @Schema(minLength=1,maxLength=160) String title, UUID projectId,
    @Schema(accessMode=Schema.AccessMode.READ_ONLY) String projectName,
    @Schema(accessMode=Schema.AccessMode.READ_ONLY,types={"string","null"},format="uuid") UUID categoryId,
    @Schema(maxLength=10000) String description,
    @Schema(allowableValues={"todo","doing","done"}) String status,
    @Schema(allowableValues={"normal","high"}) String priority,
    @Schema(maxLength=40) String tag,
    @Schema(accessMode=Schema.AccessMode.READ_ONLY,types={"string","null"},format="date-time") Instant deletedAt,
    @Schema(accessMode=Schema.AccessMode.READ_ONLY,types={"object","null"}) com.kopite.devspace.task.application.model.TaskSourceSnapshot source) {
    public TaskResponse(UUID id,long revision,Instant createdAt,Instant updatedAt,String title,UUID projectId,String projectName,UUID categoryId,
            String description,String status,String priority,String tag,Instant deletedAt) { this(id,revision,createdAt,updatedAt,title,projectId,projectName,categoryId,description,status,priority,tag,deletedAt,null); }
    public static TaskResponse from(TaskSnapshot t) {
        return new TaskResponse(t.id(),t.revision(),t.createdAt(),t.updatedAt(),t.title(),t.projectId(),
            t.projectName(),t.categoryId(),t.description(),t.status(),t.priority(),t.tag(),t.deletedAt(),t.source());
    }
}
