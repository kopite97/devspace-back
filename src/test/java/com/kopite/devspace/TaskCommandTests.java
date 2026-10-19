package com.kopite.devspace;
import com.kopite.devspace.project.application.command.CreateProjectCommand;
import com.kopite.devspace.project.application.command.ProjectCommandService;
import com.kopite.devspace.project.application.command.UpdateProjectCommand;
import com.kopite.devspace.task.application.command.CreateTaskCommand;
import com.kopite.devspace.task.application.command.TaskCommandService;
import com.kopite.devspace.task.application.command.UpdateTaskCommand;
import com.kopite.devspace.task.application.exception.TaskNotFoundException;
import com.kopite.devspace.task.application.model.TaskSnapshot;
import com.kopite.devspace.task.application.port.TaskCreateReplayStore;
import com.kopite.devspace.task.domain.Task;
import com.kopite.devspace.task.domain.TaskConflictException;
import com.kopite.devspace.task.domain.TaskRepository;
import com.kopite.devspace.task.domain.TaskValidationException;

import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TaskCommandTests {
    private final TaskCommandService tasks;
    private final ProjectCommandService projects;
    private final UserWorkspaceCreationService users;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final com.kopite.devspace.auth.application.CurrentUserService currentUser;
    private final com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository workspaces;
    private final com.kopite.devspace.project.domain.ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final tools.jackson.databind.json.JsonMapper json;

    @Autowired
    TaskCommandTests(TaskCommandService tasks, ProjectCommandService projects, UserWorkspaceCreationService users,
                     JdbcTemplate jdbc, PlatformTransactionManager manager,
                     com.kopite.devspace.auth.application.CurrentUserService currentUser,
                     com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository workspaces,
                     com.kopite.devspace.project.domain.ProjectRepository projectRepository,
                     TaskRepository taskRepository, tools.jackson.databind.json.JsonMapper json) {
        this.tasks = tasks;
        this.projects = projects;
        this.users = users;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.currentUser = currentUser;
        this.workspaces = workspaces;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.json = json;
    }

    private UUID owner() { return users.createOrReuse("task-command", UUID.randomUUID().toString(), "Owner").user().getId(); }
    private UUID project(UUID owner) {
        return projects.create(owner, UUID.randomUUID().toString(), new CreateProjectCommand("Project", null, "Java", null, null, null)).id();
    }
    private CreateTaskCommand input(UUID project) { return new CreateTaskCommand("Task", project, null, null, null, null); }
    private UpdateTaskCommand change(long revision, UUID project) { return new UpdateTaskCommand(revision, null, project, null, "doing", null, null); }
    private void archive(UUID user, UUID id) {
        projects.update(user, id, new UpdateProjectCommand(1, null, null, null, null, null, null, "archived"));
    }
    private long counter(UUID user) { return jdbc.queryForObject("select data_revision from workspaces where owner_user_id=?", Long.class, user); }

    @Test
    void archivedRelationsOwnershipAndCounters() {
        UUID user = owner();
        UUID project = project(user);
        UUID foreign = owner();
        UUID foreignProject = project(foreign);
        var task = tasks.create(user, "create", input(project));
        archive(user, project);
        assertEquals("PROJECT_ARCHIVED", assertThrows(TaskConflictException.class, () -> tasks.create(user, "new", input(project))).getCode());
        var changed = tasks.update(user, task.id(), change(1, project));
        assertEquals(2, changed.revision());
        assertThrows(TaskNotFoundException.class, () -> tasks.create(user, "foreign", input(foreignProject)));
        assertThrows(TaskNotFoundException.class, () -> tasks.update(user, task.id(), change(2, foreignProject)));
        assertThrows(TaskNotFoundException.class, () -> tasks.update(foreign, task.id(), change(2, null)));
        assertThrows(TaskNotFoundException.class, () -> tasks.delete(foreign, task.id(), 2));
        assertThrows(TaskNotFoundException.class, () -> tasks.restore(foreign, task.id(), 2));
        assertThrows(TaskNotFoundException.class, () -> tasks.delete(user, UUID.randomUUID(), 2));
        var deleted = tasks.delete(user, task.id(), 2);
        assertNotNull(deleted.deletedAt());
        SnapshotAssertions.assertDataEquals(task, tasks.create(user, "create", input(project)));
        assertEquals(5, counter(user));
        var restored = tasks.restore(user, task.id(), 3);
        assertNull(restored.deletedAt());
        assertEquals(4, restored.revision());
        assertEquals(6, counter(user));
        assertEquals(1L, jdbc.queryForObject("select revision from workspaces where owner_user_id=?", Long.class, user));
        assertEquals(2L, jdbc.queryForObject("select revision from projects where id=?", Long.class, project));
        UUID active = project(user);
        assertEquals(active, tasks.update(user, task.id(), change(4, active)).projectId());
        assertEquals("PROJECT_ARCHIVED", assertThrows(TaskConflictException.class, () -> tasks.update(user, task.id(), change(5, project))).getCode());
    }

    @Test
    void concurrentCreateAndStaleWritesHaveOneWinner() throws Exception {
        UUID user = owner();
        UUID project = project(user);
        var same = race(() -> tasks.create(user, "same", input(project)), () -> tasks.create(user, "same", input(project)));
        assertInstanceOf(TaskSnapshot.class, same.getFirst());
        SnapshotAssertions.assertDataEquals(same.getFirst(), same.getLast());
        assertEquals(2, counter(user));
        TaskSnapshot task = (TaskSnapshot) same.getFirst();
        var updates = race(() -> tasks.update(user, task.id(), change(1, null)), () -> tasks.delete(user, task.id(), 1));
        assertEquals(1, updates.stream().filter(TaskSnapshot.class::isInstance).count());
        assertEquals(1, updates.stream().filter(TaskConflictException.class::isInstance).count());
        assertEquals(3, counter(user));
        var different = race(() -> tasks.create(user, "different", input(project)),
                () -> tasks.create(user, "different", new CreateTaskCommand("Other", project, null, null, null, null)));
        assertEquals(1, different.stream().filter(TaskSnapshot.class::isInstance).count());
        assertEquals(1, different.stream().filter(TaskConflictException.class::isInstance).count());
    }

    @Test
    void archiveRacesWithCreateAndReassignment() throws Exception {
        UUID user = owner();
        UUID project = project(user);
        var createRace = race(() -> tasks.create(user, "race", input(project)), () -> { archive(user, project); return "archived"; });
        assertTrue(createRace.contains("archived"));
        Object created = createRace.getFirst();
        assertTrue(created instanceof TaskSnapshot || created instanceof TaskConflictException);
        if (created instanceof TaskConflictException conflict) assertEquals("PROJECT_ARCHIVED", conflict.getCode());
        UUID original = project(user);
        UUID target = project(user);
        var task = tasks.create(user, "move", input(original));
        var moveRace = race(() -> tasks.update(user, task.id(), change(1, target)), () -> { archive(user, target); return "archived"; });
        assertTrue(moveRace.contains("archived"));
        assertTrue(moveRace.getFirst() instanceof TaskSnapshot || moveRace.getFirst() instanceof TaskConflictException);
        if (moveRace.getFirst() instanceof TaskConflictException conflict) assertEquals("PROJECT_ARCHIVED", conflict.getCode());
    }

    @Test
    void retryExpiryRawValuesAndRollback() {
        UUID user = owner();
        UUID project = project(user);
        var task = tasks.create(user, "key", input(project));
        SnapshotAssertions.assertDataEquals(task, tasks.create(user, "key", input(project)));
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(TaskConflictException.class,
                () -> tasks.create(user, "key", new CreateTaskCommand("Task", project, "", null, null, null))).getCode());
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(TaskConflictException.class,
                () -> tasks.create(user, "key", new CreateTaskCommand(" Task ", project, null, null, null, null))).getCode());
        assertThrows(TaskValidationException.class, () -> tasks.create(user, " ", input(project)));
        assertEquals(2, counter(user));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> {
            tasks.create(user, "rollback", input(project));
            throw new IllegalStateException("rollback");
        }));
        assertEquals(2, counter(user));
        assertEquals(0L, jdbc.queryForObject("select count(*) from task_create_idempotency where key='rollback'", Long.class));
        jdbc.update("update task_create_idempotency set created_at=now()-interval '25 hours',expires_at=now()-interval '1 hour' where key='key'");
        assertNotEquals(task.id(), tasks.create(user, "key", input(project)).id());
        UUID other = owner();
        var isolated = tasks.create(other, "key", input(project(other)));
        assertNotEquals(task.id(), isolated.id());
    }

    @Test
    void replayStorageFailureRollsBackAndJsonPropertyOrderDoesNotChangeOutcome() {
        UUID user = owner();
        UUID project = project(user);
        String firstJson = "{\"title\":\"Task\",\"projectId\":\"" + project + "\"}";
        String secondJson = "{ \"projectId\": \"" + project + "\", \"title\": \"Task\" }";
        var first = tasks.create(user, "order", json.readValue(firstJson, CreateTaskCommand.class));
        SnapshotAssertions.assertDataEquals(first, tasks.create(user, "order", json.readValue(secondJson, CreateTaskCommand.class)));
        var delegate = new com.kopite.devspace.task.infrastructure.persistence.TaskCreateReplayAdapter(jdbc, json);
        TaskCreateReplayStore failing = new TaskCreateReplayStore() {
            public java.util.Optional<Replay> find(UUID workspace, String key) { return delegate.find(workspace, key); }
            public void removeExpired(UUID workspace, java.time.Instant now) { delegate.removeExpired(workspace, now); }
            public void save(UUID workspace, String key, String hash, TaskSnapshot result, java.time.Instant now) {
                delegate.save(workspace, key, hash, result, now);
                throw new IllegalStateException("simulated replay storage failure");
            }
        };
        var service = new TaskCommandService(currentUser, workspaces, projectRepository, taskRepository, failing, java.time.Clock.systemUTC(), org.mockito.Mockito.mock(com.kopite.devspace.task.application.port.TaskSourceAccess.class));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> service.create(user, "storage-failure", input(project))));
        assertEquals(2, counter(user));
        assertEquals(1L, jdbc.queryForObject("select count(*) from tasks where project_id=?", Long.class, project));
        assertEquals(0L, jdbc.queryForObject("select count(*) from task_create_idempotency where key='storage-failure'", Long.class));
    }

    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var futures = List.of(first, second).stream().map(call -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try { return (Object) call.call(); } catch (RuntimeException ex) { return ex; }
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.getFirst().get(20, TimeUnit.SECONDS), futures.getLast().get(20, TimeUnit.SECONDS));
        }
    }
}
