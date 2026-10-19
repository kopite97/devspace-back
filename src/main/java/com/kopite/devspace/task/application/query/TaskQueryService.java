package com.kopite.devspace.task.application.query;
import com.kopite.devspace.task.application.exception.TaskNotFoundException;
import com.kopite.devspace.task.application.model.TaskCursor;
import com.kopite.devspace.task.application.model.TaskSnapshot;
import com.kopite.devspace.task.application.port.TaskCursorCodec;
import com.kopite.devspace.task.application.port.TaskSearchRepository;
import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.project.domain.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TaskQueryService {
    private final CurrentUserService currentUser;
    private final com.kopite.devspace.projectcategory.application.CategoryFilterOwnership categoryOwnership;
    private final ProjectRepository projects;
    private final TaskSearchRepository search;
    private final TaskCursorCodec cursors;
    private final Clock projectClock;
    private final com.kopite.devspace.task.application.port.TaskSourceAccess sources;
    public record Page(List<TaskSnapshot> items, long total, String nextCursor, Long dataRevision, boolean externalDataUnavailable) {
        public Page(List<TaskSnapshot> items,long total,String nextCursor,Long dataRevision) { this(items,total,nextCursor,dataRevision,false); }
    }
    public record Stats(Map<String, Long> counts, long total, Instant asOf, Long dataRevision, boolean externalDataUnavailable) {
        public Stats(Map<String,Long> counts,long total,Instant asOf,Long dataRevision) {this(counts,total,asOf,dataRevision,false);}
    }

    public TaskSnapshot get(UUID userId, UUID id) {
        UUID workspace = currentUser.resolve(userId).workspace().getId();
        return search.findOwned(workspace, id).orElseThrow(TaskNotFoundException::new).observed(currentUser.resolve(userId).workspace().getDataRevision());
    }
    public Page list(UUID userId, TaskListFilter filter, String cursor) {
        UUID workspace = ownedWorkspace(userId, filter);
        filter = filter.withAccessScope(sources.scopeVersion(workspace));
        var after = cursor == null ? null : cursors.decode(workspace, filter, cursor);
        long total = search.count(workspace, filter);
        var rows = search.page(workspace, filter, after);
        var items = rows.stream().limit(filter.limit()).toList();
        String next = null;
        if (rows.size() > filter.limit()) {
            var last = items.getLast();
            next = cursors.encode(workspace, filter, new TaskCursor(last.createdAt(), last.id()));
        }
        return new Page(items, total, next, currentUser.resolve(userId).workspace().getDataRevision(),sources.unavailable(workspace));
    }
    public Stats stats(UUID userId, TaskListFilter filter) {
        if (filter.deleted() || filter.status() != null)
            throw new com.kopite.devspace.task.domain.TaskValidationException("filters", "stats accepts no deleted or status filter");
        UUID workspace = ownedWorkspace(userId, filter);
        var counts = search.counts(workspace, filter);
        return new Stats(counts, counts.values().stream().mapToLong(Long::longValue).sum(), projectClock.instant(), currentUser.resolve(userId).workspace().getDataRevision(),sources.unavailable(workspace));
    }
    private UUID ownedWorkspace(UUID userId, TaskListFilter filter) {
        UUID workspace = currentUser.resolve(userId).workspace().getId();
        categoryOwnership.validate(workspace,filter.category());
        if (filter.projectId() != null) projects.findOwned(workspace, filter.projectId()).orElseThrow(TaskNotFoundException::new);
        return workspace;
    }
}
