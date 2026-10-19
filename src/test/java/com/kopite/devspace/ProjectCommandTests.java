package com.kopite.devspace;
import com.kopite.devspace.project.application.command.CreateProjectCommand;
import com.kopite.devspace.project.application.command.ProjectCommandService;
import com.kopite.devspace.project.application.command.UpdateProjectCommand;
import com.kopite.devspace.project.application.exception.ProjectIdempotencyConflictException;
import com.kopite.devspace.project.application.exception.ProjectNotFoundException;
import com.kopite.devspace.project.application.model.ProjectSnapshot;

import com.kopite.devspace.auth.application.AccountDisabledException;
import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.project.domain.ProjectRepository;
import com.kopite.devspace.project.domain.ProjectRevisionConflictException;
import com.kopite.devspace.project.domain.ProjectValidationException;
import com.kopite.devspace.project.infrastructure.persistence.ProjectCreateReplayAdapter;
import com.kopite.devspace.user.application.UserWorkspaceCreationResult;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class ProjectCommandTests {
    private final ProjectCommandService commands;
    private final UserWorkspaceCreationService users;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CurrentUserService currentUser;
    private final PersonalWorkspaceRepository workspaces;
    private final ProjectRepository projects;
    private final JsonMapper json;
    @Autowired private com.kopite.devspace.projectcategory.domain.ProjectCategoryRepository categories;

    @Autowired
    ProjectCommandTests(ProjectCommandService commands, UserWorkspaceCreationService users, JdbcTemplate jdbc,
                        PlatformTransactionManager manager, CurrentUserService currentUser,
                        PersonalWorkspaceRepository workspaces, ProjectRepository projects, JsonMapper json) {
        this.commands = commands;
        this.users = users;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.currentUser = currentUser;
        this.workspaces = workspaces;
        this.projects = projects;
        this.json = json;
    }

    @Test
    void sameKeyRaceCreatesOneRowAndDifferentBodyRaceConflicts() throws Exception {
        var owner = owner();
        var results = race(() -> commands.create(owner.user().getId(), "same-key", input("one")),
                () -> commands.create(owner.user().getId(), "same-key", input("one")));
        assertInstanceOf(ProjectSnapshot.class, results.getFirst());
        SnapshotAssertions.assertDataEquals(results.getFirst(), results.getLast());
        assertCounts(owner, 1, 1, 1);

        var other = owner();
        var conflicting = race(() -> commands.create(other.user().getId(), "conflict", input("one")),
                () -> commands.create(other.user().getId(), "conflict", input("two")));
        assertEquals(1, conflicting.stream().filter(ProjectSnapshot.class::isInstance).count());
        assertEquals(1, conflicting.stream().filter(ProjectIdempotencyConflictException.class::isInstance).count());
        assertCounts(other, 1, 1, 1);
    }

    @Test
    void sameRevisionRaceHasOneWinnerAndPreservesIndependentWorkspaceCounters() throws Exception {
        var owner = owner();
        var p = commands.create(owner.user().getId(), "key", input("before"));
        var results = race(() -> commands.update(owner.user().getId(), p.id(), change(1, "archived")),
                () -> commands.update(owner.user().getId(), p.id(), change(1, "active")));
        assertEquals(1, results.stream().filter(ProjectSnapshot.class::isInstance).count());
        assertEquals(1, results.stream().filter(ProjectRevisionConflictException.class::isInstance).count());
        assertEquals(2L, jdbc.queryForObject("select revision from projects where id=?", Long.class, p.id()));
        assertCounts(owner, 1, 1, 2);
        var unarchived = commands.update(owner.user().getId(), p.id(), change(2, "active"));
        assertEquals(3, unarchived.revision());
        assertEquals(p.createdAt(), unarchived.createdAt());
        assertEquals("active", unarchived.status());
        assertCounts(owner, 1, 1, 3);
    }

    @Test
    void retriesPersistAcrossFreshServicesAndReturnOriginalSnapshotAfterEdits() {
        var owner = owner();
        var p = commands.create(owner.user().getId(), "persisted", input("original"));
        commands.update(owner.user().getId(), p.id(), change(1, "archived"));
        var fresh = freshService(Clock.systemUTC());
        SnapshotAssertions.assertDataEquals(p, transaction.execute(tx -> fresh.create(owner.user().getId(), "persisted", input("original"))));
        assertEquals(201, jdbc.queryForObject("select response_status from project_create_idempotency where workspace_id=?", Integer.class, owner.workspace().getId()));
        String stored = jdbc.queryForObject("select response_body from project_create_idempotency where workspace_id=?", String.class, owner.workspace().getId());
        SnapshotAssertions.assertDataEquals(p, json.readValue(stored, ProjectSnapshot.class));
        assertCounts(owner, 1, 1, 2);
        // No public deletion API exists. A retained retry result must not resurrect even an administratively removed row.
        jdbc.update("delete from projects where id=?", p.id());
        SnapshotAssertions.assertDataEquals(p, commands.create(owner.user().getId(), "persisted", input("original")));
        assertCounts(owner, 0, 1, 2);
    }

    @Test
    void expiryBoundaryAllowsNewCreationOnlyAtTwentyFourHours() {
        var owner = owner();
        Instant start = Instant.parse("2026-09-12T00:00:00Z");
        var first = transaction.execute(tx -> freshService(Clock.fixed(start, ZoneOffset.UTC))
                .create(owner.user().getId(), "expiring", input("original")));
        var before = transaction.execute(tx -> freshService(Clock.fixed(start.plusSeconds(86399), ZoneOffset.UTC))
                .create(owner.user().getId(), "expiring", input("original")));
        SnapshotAssertions.assertDataEquals(first, before);
        assertCounts(owner, 1, 1, 1);
        var after = transaction.execute(tx -> freshService(Clock.fixed(start.plusSeconds(86400), ZoneOffset.UTC))
                .create(owner.user().getId(), "expiring", input("new")));
        assertNotEquals(first.id(), after.id());
        assertCounts(owner, 2, 1, 2);
    }

    @Test
    void rollbackRemovesProjectReplayAndCounterAndOwnershipPrecedesRevision() {
        var owner = owner();
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> {
            commands.create(owner.user().getId(), "rollback", input("rollback"));
            throw new IllegalStateException("force rollback after replay storage");
        }));
        assertCounts(owner, 0, 0, 0);
        var p = commands.create(owner.user().getId(), "rollback", input("saved"));
        var stranger = owner();
        assertThrows(ProjectNotFoundException.class, () -> commands.update(stranger.user().getId(), p.id(), change(99, "archived")));
        assertThrows(ProjectNotFoundException.class, () -> commands.update(stranger.user().getId(), UUID.randomUUID(), change(99, "archived")));
        assertCounts(stranger, 0, 0, 0);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> {
            commands.update(owner.user().getId(), p.id(), change(1, "archived"));
            throw new IllegalStateException("force rollback after update");
        }));
        assertEquals(1L, jdbc.queryForObject("select revision from projects where id=?", Long.class, p.id()));
        assertCounts(owner, 1, 1, 1);
        jdbc.update("update users set disabled_at=now() where id=?", owner.user().getId());
        assertThrows(AccountDisabledException.class, () -> commands.create(owner.user().getId(), "rollback", input("saved")));
        assertCounts(owner, 1, 1, 1);
    }

    @Test
    void requestHashPreservesOmissionAndRawValuesAndInvalidKeysDoNotWrite() {
        var owner = owner();
        var omitted = new CreateProjectCommand("name", null, "stack", null, null, null);
        var first = commands.create(owner.user().getId(), "hash", omitted);
        SnapshotAssertions.assertDataEquals(first, commands.create(owner.user().getId(), "hash", omitted));
        assertThrows(ProjectIdempotencyConflictException.class, () -> commands.create(owner.user().getId(), "hash",
                new CreateProjectCommand("name", "", "stack", null, null, null)));
        assertThrows(ProjectIdempotencyConflictException.class, () -> commands.create(owner.user().getId(), "hash",
                new CreateProjectCommand(" name ", null, "stack", null, null, null)));
        for (String key : new String[]{null, "", " ", "two words", "한글", "x".repeat(129)}) {
            assertThrows(ProjectValidationException.class, () -> commands.create(owner.user().getId(), key, omitted));
        }
        var updated = commands.update(owner.user().getId(), first.id(), change(1, null));
        assertEquals(2, updated.revision());
        assertEquals(first.name(), updated.name());
        assertCounts(owner, 1, 1, 2);
    }

    private ProjectCommandService freshService(Clock clock) {
        return new ProjectCommandService(currentUser, workspaces, projects, new ProjectCreateReplayAdapter(jdbc, json), clock, categories,org.mockito.Mockito.mock(com.kopite.devspace.project.application.port.ProjectExternalLifecycle.class));
    }

    private List<Object> race(Callable<ProjectSnapshot> first, Callable<ProjectSnapshot> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                try { return (Object) task.call(); }
                catch (ProjectIdempotencyConflictException | ProjectRevisionConflictException expected) { return expected; }
            })).toList();
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.getFirst().get(30, TimeUnit.SECONDS), futures.getLast().get(30, TimeUnit.SECONDS));
        } finally { start.countDown(); }
    }

    private UserWorkspaceCreationResult owner() {
        return users.createOrReuse("commands", UUID.randomUUID().toString(), "Owner");
    }

    private CreateProjectCommand input(String name) {
        return new CreateProjectCommand(name, null, "Java", new BigDecimal("23.456"), null, null);
    }

    private UpdateProjectCommand change(long revision, String status) {
        return new UpdateProjectCommand(revision, null, null, null, null, null, null, status);
    }

    private void assertCounts(UserWorkspaceCreationResult owner, long projectCount, long replayCount, long dataRevision) {
        UUID workspace = owner.workspace().getId();
        assertEquals(projectCount, jdbc.queryForObject("select count(*) from projects where workspace_id=?", Long.class, workspace));
        assertEquals(replayCount, jdbc.queryForObject("select count(*) from project_create_idempotency where workspace_id=?", Long.class, workspace));
        assertEquals(dataRevision, jdbc.queryForObject("select data_revision from workspaces where id=?", Long.class, workspace));
        assertEquals(1, jdbc.queryForObject("select revision from workspaces where id=?", Integer.class, workspace));
    }
}
