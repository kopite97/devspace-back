package com.kopite.devspace.github.presentation;
import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.github.application.GitHubDetachService;
import com.kopite.devspace.task.application.query.TaskQueryService;
import com.kopite.devspace.task.application.port.TaskSourceAccess;
import com.kopite.devspace.task.application.model.TaskSourceSnapshot;
import com.kopite.devspace.github.domain.GitHubException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import java.util.UUID;

@RestController @RequiredArgsConstructor @SecurityRequirement(name="sessionCookie") @Tag(name="GitHub disconnection")
public class GitHubDetachController {
    private final GitHubDetachService service;private final TaskQueryService tasks;private final TaskSourceAccess access;
    public record Cleanup(@Positive long revision,@NotNull @Pattern(regexp="purge") String disposition){}
    @DeleteMapping("/api/v2/projects/{projectId}/github/repositories/{linkId}") @Operation(summary="Disconnect a repository in bounded batches",description="disposition=convert retains accessible Task copies; purge permanently removes source Tasks. Repeating with current revision resumes partial work. No private content returned.")
    public ResponseEntity<GitHubSyncController.AcceptedJob> detach(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID linkId,@RequestParam long revision,@RequestParam String disposition){return accepted(service.request(user(auth),projectId,linkId,revision,disposition));}
    @PostMapping("/api/v2/projects/{projectId}/github/repositories/{linkId}/cleanup-requests") @Operation(summary="Permanently purge protected source data without disclosing it",description="Requires service ownership, CSRF, current link revision and explicit disposition=purge; provider access is not required.")
    public ResponseEntity<GitHubSyncController.AcceptedJob> cleanup(Authentication auth,@PathVariable UUID projectId,@PathVariable UUID linkId,@Valid @RequestBody Cleanup request){return accepted(service.request(user(auth),projectId,linkId,request.revision(),request.disposition()));}
    @GetMapping("/api/v2/tasks/{taskId}/github-issue") @Operation(summary="Read an accessible Task source")
    public ResponseEntity<TaskSourceSnapshot> source(Authentication auth,@PathVariable UUID taskId){UUID user=user(auth);access.prepare(user);var source=tasks.get(user,taskId).source();if(source==null)throw new GitHubException("RESOURCE_NOT_FOUND");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(source);}
    @DeleteMapping("/api/v2/tasks/{taskId}/github-issue") @Operation(summary="Convert one accessible source Task to a local Task",description="Current Task revision required. Retains content and prevents future automatic re-creation; returns no source content.")
    public ResponseEntity<Void> convert(Authentication auth,@PathVariable UUID taskId,@RequestParam long revision){service.convert(user(auth),taskId,revision);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    private static UUID user(Authentication auth){return ((InternalUserPrincipal)auth.getPrincipal()).userId();}
    private static ResponseEntity<GitHubSyncController.AcceptedJob> accepted(UUID job){return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new GitHubSyncController.AcceptedJob(job));}
}
