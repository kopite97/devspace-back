# PLAN-0015 Session 1: Contract and environment preflight

Date: 2026-09-23
Status: completed. Both ADRs explicitly approved by the user on 2026-09-23; Session 2 may proceed.

## Authorization and contract

The user designated PLAN-0015 as the final source of truth, approved synchronization of both requirements documents, and then approved execution of the Plan. PLAN-0015 is active. Existing requirements edits are preserved. This does not authorize migrating the existing local database or a remote deployment.

The current contract uses UUIDs, `/api/v2`, `{items,total,nextCursor}`, opaque JSON arrays, mandatory PUT attachmentIds, LocalFileStorage, and the Plan's limits/errors. Parent-delete 200 commits metadata deletion and durable binary cleanup; TEMP direct-delete 200 completes binary and metadata deletion. Published documents remain editable and repeat Publish is unchanged. These details are now reflected in the common contract.

Two architectural choices explicitly identified in Session 1 are presented as separate proposed records:

- [DECISION-0005](../../decisions/DECISION-0005-document-space-access.md): owner-neutral access capability and Journal adapter.
- [DECISION-0006](../../decisions/DECISION-0006-attachment-deletion-recovery.md): durable deletion claims/retry without a generic outbox or distributed transaction.

The Decision Guide's referenced template does not exist anywhere under docs. Both records follow the established DECISION-0004 structure. Shared agent guides remain unchanged. Both records were subsequently explicitly approved by the user; the decision gate is satisfied.

## Repository and migration baseline

- Java 21, Spring Boot 4.1.1, springdoc 3.1.0; existing JPA/Flyway/PostgreSQL and Jackson 3 conventions.
- Current migration maximum V18; V16/V17 retain staged source locations and unconditional final artifact packaging. V19 is currently available. Planned sequence: V19 independent tables (Session 2), V20 Journal backfill/FK with writer change (Session 3), V21 deletion work (Session 5); recheck before adding each migration.
- Journal uses UUID scalar ownership, a composite owned-Project FK, server microsecond audit, revision checks, 24-hour create replay and physical deletion. No Document/Attachment/FileStorage/Scheduler implementation exists.
- User references use restrictive deletion. New author/creator references should preserve that policy; audit is not ownership.
- Backfill fixtures will cover an empty V18 database, two owned Journals with distinct audit/body/revision and historical replay values, and a 1,001-Journal fixture across multiple owners. The populated fixture records all prior migration checksums and Journal/replay values before upgrade, then compares them after upgrade. This is synthetic validation, not a count or assessment of live data.
- Session 2 must leave the current Journal writer functional. Session 3 introduces FK/backfill/NOT NULL and the new writer together. User/Project/Journal history and previously applied migrations must not be rewritten.
- No existing local database was started or queried for migration, and no production migration file was added during preflight.

## Validation performed

Existing Journal baseline command (Java 21):

```powershell
./gradlew.bat test --tests '*JournalPersistenceTests' --tests '*JournalCommandTests' --tests '*JournalQueryTests' --tests '*JournalApiTests' --tests '*JournalOpenApiTests' --no-daemon
```

Result: BUILD SUCCESSFUL; 16 tests, zero failures/errors/skips, using PostgreSQL 16.4 Testcontainers. Counts: Persistence 4, Command 5, Query 2, API 4, OpenAPI 1. XML evidence is under `build/test-results/test/TEST-com.kopite.devspace.Journal*.xml`. No production security or test profile relaxation was made.

Docker Desktop was initially stopped and was started with tool approval. Docker engine 28.3.2 and existing postgres:16.4 / eclipse-temurin:21-jdk-jammy images were available. No new project dependency was introduced.

An isolated Java source probe at `.gradle/document-editor-preflight/StorageProbe.java` exercised:

| Environment | Checks | Result |
| --- | --- | --- |
| Windows host, Java 21 | Root marker, same-filesystem atomic move, exclusive CREATE_NEW, normalized traversal rejection, file read/write/delete, second-process read | Passed |
| Linux container, named volume, UID/GID 10001 | Same file operations and read from a newly created container | Passed |
| Linux container, Windows host bind mount, UID/GID 10001 | Same file operations and read from a newly created container | Passed |

Probe root: `.gradle/document-editor-preflight/host-root`; bind root: `.gradle/document-editor-preflight/bind-root`; isolated named volume: `devspace-plan0015-preflight`. All probe containers used `--rm`; the named volume and probe bytes remain preserved. These paths are test artifacts, not the eventual application's storage configuration.

This demonstrates filesystem feasibility only. It is not validation of an implemented LocalFileStorage adapter, symlink/junction-race defenses, actual multipart upload, cleanup, or application restart recovery. Those require Sessions 5–9. Production root initialization must still require an explicit marker, restricted permissions, no fallback and no public static mapping. No FileStorage setting was yet added to the application's `.env`.

## Resolved gate

PLAN-0015 Session 1 and Decision Guide explicitly require approval of the concrete ADRs before adopting the two long-term architectural boundaries. Both reviewable records were explicitly approved and accepted on 2026-09-23. Later session items remain unchecked until implemented and validated. Plan execution approval and completed environment checks are retained and do not need to be repeated.
