# PLAN-0014: Final Schema Consolidation and Bridge Retirement

Status: `completed`
Date: 2026-09-17
Completed: 2026-09-22

## Goal

Bring the local Compose PostgreSQL database to the supported final schema and retire the Category transition bridge in the local/repository release workflow. Make ordinary Gradle/IDE/Docker builds independent of categoryStage without weakening Flyway, replay retention, ownership or Widget migration safeguards.

Consolidation means a common schema and runtime policy across environments, not merging database contents, copying production data into development, or moving Render to a personal server. The repository target is currently V18; recheck the migration inventory before execution and review any newer migration before changing the target.

## Scope

- Inventory the local Compose database, its application instances and local release settings. On 2026-09-17 the user explicitly excluded Render inspection from this execution. Render remains active and unverified, not decommissioned or certified by local results; no Render access, migration, configuration change or deployment is included.
- Rehearse existing V16/V17/V18 transitions and recovery on isolated databases, then execute only separately approved environment operations.
- After the environment gate, remove the temporary stage argument/guard and make final migration packaging unconditional. Retire the configurable legacy HTTP replay path while retaining authenticated 410 tombstones and historical data compatibility needed by normal APIs.
- Preserve PostgreSQL data, existing migration contents/names/versions/checksums, canonical idempotency namespaces, stored response bytes, expiry/hash behavior, no-store, CSRF, current APIs and Widget/Dashboard boundaries.
- Exclude data merging, DB resets, destructive legacy JSONB/mapping/replay cleanup, shortened replay windows, new integrations, dependencies, infrastructure, frontend implementation and shared agent-guide changes.
- Execution was approved on 2026-09-17 for repository work and isolated validation within this plan. This does not itself authorize production access, backup/export, migration, deployment or database-specific mapping choices. Obtain explicit environment/action authorization after a concrete execution package is ready; reuse authorization already supplied for that exact scope.

## Historical Repository Evidence and Initial Failure

This table records the pre-execution baseline. Completed Session results below describe the final implementation.

| Evidence | Confirmed repository behavior |
| --- | --- |
| [build.gradle](../../build.gradle) | The original processResources guard blocked IDE classes. The bounded compilation fix below moves it to packaging/test/bootRun. The guard checks an argument, not DB state. |
| [Dockerfile](../../Dockerfile) | Requires CATEGORY_STAGE=final before bootJar. Current Widget source cannot produce bridge artifacts. |
| [application.yml](../../src/main/resources/application.yml) | Imports the ignored local .env and generated category-transition.properties; Hibernate uses validate. Default startup runs packaged Flyway migrations. |
| [V16](../../src/main/migration-stages/cutover/V16__category_only_cutover.sql) | Requires reviewed mapping/preconditions for existing data and records a durable cutover deadline. A populated cutover requires at least 24 hours plus the stored clock margin and applicable legacy expiry. |
| [V17](../../src/main/migration-stages/contract/V17__retire_classification_scope.sql) | Refuses retirement before category_cutover_state.retire_after or the latest scope-bearing historical replay expiry plus margin. Removes old classification columns, not replay history. |
| [V18](../../src/main/resources/db/migration/V18__independent_widgets_and_placements.sql) | Requires V17, preserves legacy JSONB/mapping, performs deterministic Widget backfill and advances workspace counters transactionally. |
| [LegacyReplayController](../../src/main/java/com/kopite/devspace/compatibility/presentation/LegacyReplayController.java) | The packaged flag is false, but the controller still has an overridable enabled branch and a true fallback. Removing the generated property alone could re-enable replay. |
| [Existing build evidence](../reviews/plan0013/build-policy.md), [Widget validation](../reviews/plan0013/session-5.md) | Isolated final migration/build checks passed; actual environment migration and frontend readiness were not verified. |

Follow [the migration guide](../agents/FLYWAY.md), [Category rollout](../project-category-only-rollout.md), [Widget rollout](../widget-rollout.md), [DECISION-0002](../decisions/DECISION-0002-project-category-only-classification.md) and [DECISION-0004](../decisions/DECISION-0004-independent-widget-and-dashboard-placement.md). Execution retired the existing transition after local admission and updated only DECISION-0004's approved release-policy wording. Its Widget architecture, historical evidence and all shared agent guides are unchanged.

## Target Design and Gates

### Environment inventory

Create an approved inventory using non-secret environment aliases. For each database record: purpose/owner, exact DB and schema identity in protected operational records, active writers and restart paths, deployed artifact/source digest, Flyway versions/checksums/success, relevant schema shape, cutover deadline/margin, legacy replay expiry, data volume, mapping requirements, backup/restore readiness and compatible frontend/API usage. Inventory CI and developer run configurations too.

Do not log connection passwords, tokens, raw production response bodies or production mapping manifests in the repository. Prefer authorized read-only metadata/count queries and protected evidence references. Do not start an application against an unknown database merely to discover its version: startup can migrate it. A schema version alone is insufficient; verify checksums, actual constraints, replay state and deployed writers. A database still needed for recovery is an explicit restore case, not silently omitted from the inventory.

| Observed state | Required route |
| --- | --- |
| Fresh and proven empty | Rehearse V1-V18 with no starter data or fabricated legacy window. Never classify an existing user DB as disposable without approval. |
| Earlier than V16 | Validate the history and supported upgrade path, prepare approved V16 mappings on a consistent snapshot, drain old writers, and use preserved stage-compatible tooling/artifacts. Do not point the Widget application at an unprepared DB. |
| V16, drain incomplete | Keep the already-approved compatible bridge available for its required replay window. Read the durable DB deadline and applicable latest legacy expiry; elapsed wall time or an empty replay table alone does not satisfy the gate. |
| V16, drain complete | Recheck all writers and replay preconditions under the planned maintenance boundary; apply unchanged V17, then V18 with reconciliation. |
| V17 | Verify history/shape and Widget preflight, stop legacy Dashboard writers, then apply V18. |
| V18 | Validate only; do not repeat backfill or increment counters again. Confirm the artifact and clients actually use final contracts. |
| Divergent/failed history, unexpected later migration, unidentified writer | Stop that environment's transition and investigate. No repair, baseline, out-of-order migration or guessed mapping. |

For V16 use the existing reviewed manifest mechanism, including expectedDataRevision, Dashboard revision/JSONB digest and Link mappings. Identity must not be inferred from names, URLs or retired scope labels. Session-local Flyway initialization uses the existing indexed spring.flyway.init-sqls mechanism; no new environment variable is required. Remove one-time settings only after their successful transition and evidence capture. Production values remain protected.

### Retirement gate

Before enabling the simplified release locally, every in-scope inventory row must be either (a) verified at the approved final schema with compatible writers/clients, or (b) explicitly decommissioned with user-approved data retention and restart prevention. An offline old database is not automatically decommissioned. Required bridge replay windows must have elapsed, pending client creates must be resolved according to their original contract, and old instances/jobs/rollback automation must not resume writes. For local execution, investigation and isolated tests need no application downtime. Restrict old writes/restarts only across actual migration and backend replacement; stop a running backend if present, not the IDE itself. A zero-connection observation is not a restart fence. Frontend readiness is a client enablement prerequisite: keep old clients from the migrated Dashboard until adapted, and report the dependency rather than implement it here.

An additional local writer or in-scope old DB reopens the gate. Render does not block local work under the revised scope, but a future Render release must independently verify its history, drain deadline, writers, backup and frontend compatibility using the same admission procedure. Local rehearsal proves migration behavior, not remote state. Preserve immutable pre-Widget source/artifacts and migration instructions as recovery evidence; ending active bridge service/support does not mean deleting the only recovery path. Future restores of old backups require an isolated, stage-aware upgrade before admission to the normal final runtime.

### Build and runtime simplification

- Remove categoryStage and validateCategoryStage from Gradle and CATEGORY_STAGE from Docker. Do not replace them with a default final flag or an IDE-only exception. Normal help/classes/test/check/bootJar/bootRun and Docker builds use one supported schema policy.
- Keep V16/V17 files in their existing staged source directories and copy them unconditionally to db/migration. Their current packaging is already unconditional after the guard; directory relocation offers no required benefit and risks history/duplicate-resource mistakes. V1-V18 must occur exactly once in the artifact.
- Replace generated category-transition.properties with removal of both its configuration imports and its consumers in the same change. The final runtime must have no switch that re-enables legacy HTTP replay. Update isolated test configuration too; verify both incremental builds with stale generated output and clean builds.
- Retain the final 410 behavior, request binding and authentication/CSRF/disabled-account/error precedence of retired routes. Do not accidentally retire auth, project-category or new Widget v1 APIs. Keep the separate Dashboard v2 tombstone.
- Remove the enabled HTTP replay branch and only demonstrably exclusive, unreachable bridge code after dependency review. Preserve historical snapshot readers still needed by retained replay rows, normal v2 key-conflict checks and canonical /api/v1/... persistence namespaces. Do not bulk-delete code or data because its name contains legacy or scope.
- No new SQL migration is expected: this plan applies existing migrations and simplifies packaging/runtime. Any newly discovered data repair or schema requirement is a stop-and-review item, not permission to edit V16-V18 or invent V19.

## Expected Changes

| Area | Planned change |
| --- | --- |
| build.gradle, Dockerfile | Remove stage selection, retain deterministic final resource packaging and normal Java 21 build behavior. |
| src/main/resources/application.yml; src/test/resources/widget-isolated-test.yml | Remove the obsolete generated property import with its runtime consumers. Preserve .env handling, DB credentials and Hibernate validate. |
| compatibility/presentation/LegacyReplayController.java and proven bridge-only collaborators | Make retirement unconditional; preserve tombstones and shared replay semantics. Exact removals follow dependency analysis. |
| CategoryOnlyReplayApiTests, ProjectLegacyReplayTests, FinalCategoryRuntimeTests, CategoryCutoverMigrationTests, WidgetMigrationTests and OpenAPI/security tests | Separate preserved historical evidence from supported final runtime; never disable migration/hash/retention tests to make deletion pass. |
| docker-deployment.md, project-category-only-rollout.md, widget-rollout.md, DECISION-0004 | Publish current no-stage commands, explicit archive-only bridge instructions, restore route and approved retirement evidence. Preserve historical checkpoints. |
| docs/reviews/plan0014/ | Sanitized inventory, rehearsal/build results and protected operational evidence references, created during execution. |
| Existing DBs and deployment configuration | Only explicitly approved transitions and removal of obsolete build/one-time migration settings; no new infrastructure or arbitrary DB recreation. |

## Execution Sessions

Executed on 2026-09-22 after the user requested completion. The refreshed inventory found the local DB already at V18; the approved already-final route therefore required validation, not a schema transition. [Complete evidence and limitations](../reviews/plan0014/completion.md).

### Interim fix: IDE compilation

The 2026-09-17 change moved stage validation out of classes/processResources. That historical bounded fix is superseded by Session 4's complete removal of stage selection. The initial V16 DB observation is likewise historical, not the state observed during this execution.

### Session 1: Inventory and Transition Contract

- [x] Rechecked guides, source dependencies, all 18 immutable migrations and preserved bridge/final artifacts.
- [x] Inspected the authorized local database read-only. Actual V1-V18 history validates against all Flyway checksums; final tables exist and both retention deadlines have elapsed.
- [x] User confirmed this is the only local DB, with no separate/restarting backend or pending creation request. No existing backend shutdown was needed.
- [x] Froze the already-V18 route: no schema operation, mapping change, business-data export or restoration. Recovery rehearsal uses synthetic data; future real restores still require explicit admission and backup verification.
- [x] Confirmed adjacent frontend source uses Dashboard v3/Widget v1. Render stays excluded and unverified.

Evidence: [refreshed Session 1 inventory](../reviews/plan0014/session-1.md). All in-scope admission facts were resolved for the already-final route; old backups are archive-only recovery cases, not supported old-schema runtimes.

### Session 2: Isolated Upgrade and Recovery Rehearsal

- [x] Rehearsed fresh, waiting V16, eligible V16, V17 and already-V18 cases in disposable PostgreSQL.
- [x] Tested manifest validation, preserved rows/replay bytes/hash/expiry, missing Categories, saved-empty/absent layouts, deterministic mappings/order and counters.
- [x] Proved expired replay cleanup cannot bypass the durable V17 deadline.
- [x] Proved V17 can remain committed when V18 fails; corrected only the synthetic invalid fixture and retried unchanged V18 successfully.
- [x] Used actual pg_dump/pg_restore for a synthetic final-schema backup, then started the final application against the restored database with Hibernate/Flyway validation and unchanged table contents.
- [x] Prepared the local execution package: validate only because V18 is already applied; compare all table digests, preserve the previous final artifact, and never rerun backfill. No migration/deployment approval package is required for this no-transition route.

The initial targeted run passed 17 migration tests; these also passed in the final full suite. [Backup restoration evidence](../reviews/plan0014/backup-restore.txt). This does not certify a real user's backup.

### Session 3: Approved Environment Convergence

- [x] Revalidated the sole active local database as V18; no Flyway migrate, backup/export, restore, mapping, maintenance drain or counter mutation was necessary.
- [x] Started the final application with a PostgreSQL read-only connection, allowing normal Flyway no-op startup and Hibernate validation. Queried its existing layout and all six Widget payloads.
- [x] Compared all 23 public tables before/after: counts and content digests, including history and replay data, are unchanged.
- [x] Verified authenticated mutation/replay/Widget smoke checks in isolated databases; kept the real workspace read-only. No production load or live-data smoke mutation occurred.
- [x] Preserved the previous explicit-final JAR/source and bridge archive; recorded restart and forward-fix/restore boundaries. No old writer is active or configured to auto-restart according to inventory and user confirmation.

Local admission is complete. Render readiness is neither required nor inferred. The existing bridge archive is retained as recovery evidence and must never run against V18.

### Session 4: Remove Transitional Build and Runtime Controls

- [x] Removed Gradle/Docker stage selectors and runtime property imports; V16/V17 remain in their original source directories and are always packaged.
- [x] Replaced conditional historical HTTP replay with authenticated 410 tombstones, preserving request binding/error precedence and the separate Dashboard v2 tombstone.
- [x] Moved seven exclusively historical collaborators into test sources without component scanning; retained their golden/concurrency tests and every normal API historical reader/canonical namespace.
- [x] Removed only the stale generated transition file during incremental resource processing.
- [x] Verified no-stage help/classes, incremental bootJar and clean check/bootJar; all 272 tests passed without skips.
- [x] Verified ordinary bootRun against an isolated V18 database and direct runtime-classpath startup. IDE UI was not driven; actual compilation/startup is the documented alternative verification.
- [x] Built Docker without a stage argument and started it against a disposable empty DB: V1-V18 and Hibernate validation passed. Copied/inspected the image JAR.
- [x] Verified clean/incremental/Docker JARs contain 18 unique unchanged migrations and no generated transition property or bridge-serving classes.
- [x] Verified the obsolete true replay property cannot enable serving; auth/CSRF/account checks, no-store, 410, v1 exceptions, v2 conflicts, historical snapshots and Widget contracts pass.
- [x] Updated release/restore documentation and only DECISION-0004's approved release-policy wording. Shared guides remain unchanged.

Evidence: [full suite](../reviews/plan0014/full-suite-results.json), [JAR](../reviews/plan0014/clean-artifact.json), [image JAR](../reviews/plan0014/docker-artifact.json), [browser/API verification](../reviews/plan0014/browser-result.json).

### Session 5: Approved Final Release and Closure

- [x] Applied the simplified workflow to this local repository; built and exercised its final artifacts. No remote deployment or persistent application-service installation is part of the local route.
- [x] Removed the completed V16 one-time initialization entry from ignored local .env, preserving all other settings and a private copy of that entry.
- [x] Verified actual local V18 startup/read compatibility and isolated authenticated API/Swagger behavior; recorded direct-classpath/Gradle evidence instead of claiming IDE UI automation.
- [x] Confirmed no runtime switch restores legacy serving and unaffected v1 auth/Category/Widget APIs still work.
- [x] Updated inventory, current commands and restore admission instructions; retained immutable archives and explicit Render/real-backup limitations.
- [x] Finished document/link checks and validation-process cleanup. Temporary servers, Chrome and validation containers are stopped/removed; the existing local DB is stopped with its volume preserved.

## Rollback and Stop Conditions

Before a schema transition, use the verified backup and stage-compatible application pair. After V16, old v1 writers are unsafe; after V17, a bridge binary is not a rollback option. If V18 fails after committed V17, retain the verified final-compatible maintenance boundary and retry/fix forward; do not reset Flyway history. After Widget writes, preserved legacy JSONB is stale and cannot silently replace current data.

The simplified build change can be reverted to the previous explicit-final Widget release on a compatible final DB. That does not justify re-enabling bridge or restoring old DB data. Any restore that discards later writes/replays requires separate loss/reconciliation approval. Stop for failed preconditions, backup failure, unidentified writers, checksum mismatch, pending retention, incompatible frontend, unsupported versions or unexpected data changes. Report the concrete blocker; do not shorten the plan's completion criteria to hide it.

## Final Validation

- [x] Every required local/repository session is complete with evidence; the sole in-scope DB is verified final. Render remains excluded.
- [x] No categoryStage/CATEGORY_STAGE selection is required in current Gradle, IDE, Docker or deployment workflows.
- [x] No runtime flag can restore legacy HTTP replay service; historical data and required shared readers remain preserved.
- [x] Flyway history/checksums, Hibernate validate, security, normal idempotency and Widget/API contracts remain intact.
- [x] Fresh install, populated upgrade, already-final startup, failed transition/retry and synthetic backup/application recovery checks pass. Actual-backup restoration was unnecessary for the no-transition local route and is not claimed.
- [x] No failures caused by this plan remain; no unapproved data cleanup, frontend work, infrastructure or guide changes occurred.

### API Validation

No new endpoint or normal response contract is planned. Preserve and regress existing final 410 behavior, auth/CSRF/error precedence and normal OpenAPI inventory/schemas/Swagger rendering. Endpoint-addition checklist items are not applicable unless a reviewed scope change introduces one.

## Remaining Operational Limits

- Actual local V18 history, checksums, read compatibility, retention and writer inventory were verified on 2026-09-22. No local schema transition or real-data backup/export was needed or performed.
- Render remains excluded, active and unverified. A future remote release needs its own admission and operational authorization; local results do not certify it.
- Synthetic pg_dump/pg_restore and application startup passed. Real protected-backup recovery was not attempted and must be verified before a future destructive restore.
- Restoring a populated older backup can require another staged replay window. Preserve that isolated recovery route even though current releases cannot serve bridge mode.
- IDE UI and a deployed frontend were not driven. No-stage compilation/direct Java startup/bootRun and an actual isolated Swagger browser workflow provide the documented alternative evidence. Production OIDC login and frontend deployment are outside scope.

## Completion

Completed on 2026-09-22 after the applicable local/repository checks passed. Execution used the already-V18 route and preserved real data. Render operations remain explicitly unperformed. Detailed evidence, alternative IDE verification and recovery limitations are retained in [the completion report](../reviews/plan0014/completion.md).
