# PLAN-0014 Session 1: Inventory and Transition Contract

Date: 2026-09-17
State: Inventory refreshed on 2026-09-22; local database is already V18 (see below).

## Repository findings

- The source inventory contains exactly V1-V18. [Source SHA-256 baseline](migration-source-sha256.json) records immutable file content for later packaging comparison. These hashes are not Flyway checksums and do not validate an actual database.
- Gradle configuration is allowed without a stage, but processResources depends on validateCategoryStage; therefore IDE classes still requires final. Docker also explicitly requires final. Removing these controls belongs to Session 4 after environment convergence.
- V16 uses an approved manifest and durable replay deadline; V17 checks both the durable deadline and applicable retained scope-bearing replay expiry. V18 requires the retired schema before Widget conversion. No migration file was changed.
- LegacyReplayController, LegacyCreateParser, LegacyReplayService, LegacyReplayStore, LegacyCreateRequest, LegacyFingerprint, CreationResource and the compatibility LegacyReplayAdapter form the enabled bridge path. Removal candidates require final reference checks during Session 4. Keep the controller's final authenticated tombstone behavior and ApiVersionRetiredException.
- Project, Task, Journal, Milestone and Link CreateReplayAdapter implementations still use canonical /api/v1/... persistence namespaces and detect historical scope-bearing responses. These are shared normal-API dependencies, not bridge deletion candidates. Link detects scope inside item; the other four detect the root field.
- CategoryOnlyReplayApiTests, ProjectLegacyReplayTests, CategoryOnlyReplayConcurrencyTests and LegacyCreationGoldenTests cover bridge serving/parsing. Their historical evidence must be preserved and migration/normal-key conflict coverage retained when supported runtime tests change. FinalCategoryRuntimeTests covers final retirement behavior.
- compose.yml declares PostgreSQL 16.4 and a named devspace-postgres-data volume. This is a configuration candidate, not proof that a container/database is active or disposable. Local IDE metadata exists; .github and .run directories were absent during inspection. Deployed writers and external CI remain unverified.
- DECISION-0004 still requires explicit final selection. Its release-policy wording remains unchanged until the retirement gate; the Widget architecture is unaffected.

## Environment inventory awaiting confirmation

| Candidate alias | Evidence | State and missing facts |
| --- | --- | --- |
| local-development | PostgreSQL compose configuration | User confirmed active use. V1-V16 successful history observed; other admission facts remain pending. |
| render (excluded) | User confirmed active PostgreSQL, then explicitly skipped its inspection | Active and unverified. No production access or deployment in this execution; separate admission required before a future release. |
| other / recovery | No confirmed inventory | User must identify any other active DB or backup requiring a supported restore route. |

No environment row is admitted or decommissioned. Authorized local read-only DB inspection was performed. No application startup, export, migration, environment-variable change or deployment was performed. Existing PLAN-0013 isolated test results are not environment evidence.

## Read-only inspection procedure

1. Confirm target aliases, execution owner, exact database/schema identity and permitted read scope. Keep addresses/credentials in protected operational records. Enumerate applications, jobs, developer launch configurations and restart/rollback automation separately; database sessions alone cannot prove absence of writers.
2. Use a database client with explicit target selection, not application startup. Start a read-only transaction; set bounded statement and lock timeouts locally. Inspect server identity and search_path, then discover tables before issuing version-specific queries. End with ROLLBACK. Do not execute Flyway migrate/repair/baseline for discovery.
3. Read Flyway history version, script, checksum and success. Compare using the matching preserved artifact/Flyway validation workflow; do not compare database Flyway checksums directly to the SHA-256 baseline. Stop for failed, divergent or unexpectedly later history.
4. Inspect information_schema/pg_catalog for classification columns, Dashboard constraints and V18 Widget/placement/mapping tables. Read only aggregate row counts and structural preconditions. Absence of a table before its migration is a route observation, not a reason to create it.
5. Where category_cutover_state exists, inspect project-category-only cutover_at, retire_after and clock_margin_seconds together with the database clock. Derive latest applicable replay expiry using the exact five-table/root-versus-item predicate in V17. Do not return keys, hashes, response bodies or credentials. A read-only snapshot is diagnostic; recheck mutable values at the authorized migration boundary.
6. Record approved mapping/backup references, not their private contents. Verify restoration readiness and artifact pairing separately; neither source inspection nor a backup filename proves a usable restore.
7. Confirm actual frontend API versions, pending creates, writer shutdown and restart controls with the environment owner. Select the PLAN route only from these observations. Produce per-environment commands and abort conditions in Session 2 after this gate closes.

## Validation and gate

Source dependency searches and migration enumeration completed. No application tests/services were needed for this inventory preparation. Source files contain 18 unique consecutive migration versions. Documentation links and source-hash evidence are checked separately before handoff.

Session 1 remains incomplete: the actual environment inventory, schema/history, retention deadlines, mapping choices, frontend readiness, backup/recovery evidence and execution authority are not confirmed. Session 2 and all implementation/operational work remain unstarted. No uncertainty is classified as a successful check.

## Authorized local observations

Compose reports PostgreSQL 16.4 running with port 5432 bound to loopback and one persistent volume. Two psql transactions used BEGIN READ ONLY, 5-second statement and 1-second lock timeouts, and ROLLBACK. Only metadata, timestamps and counts were returned.

- Flyway history: successful V1-V16 rows, no V17/V18. Source-to-database Flyway checksum validation is pending; V16 recorded checksum is 2043872006.
- category_cutover_state exists; no public table name containing widget or placement was found.
- Database observation time: 2026-09-16 15:16:09.691874 UTC (2026-09-17 00:16:09 KST).
- cutover_at: 2026-09-15 00:09:38.371217 UTC; retire_after: 2026-09-16 00:14:38.371217 UTC; margin: 300 seconds. Durable deadline elapsed.
- Latest scope-bearing replay expiry using V17's exact predicate: 2026-09-15 02:44:49.260123 UTC; its margin also elapsed at observation.
- Counts: 1 workspace, 2 projects, 0 links, 1 Dashboard. This is populated, not a disposable fresh-install target.

Provisional route: eligible V16 -> V17 -> V18, conditional on history/shape validation, writer shutdown, Widget preflight, frontend compatibility and backup/recovery readiness. Deadlines alone do not establish admission or authorize migration. Recheck mutable conditions immediately before execution. Render was not accessed. User confirmed both environments are active; neither is decommissioned.

## User scope adjustment

The user excluded Render inspection on 2026-09-17. The local gate remains mandatory; Render is no longer a blocker for local work, but local success cannot certify remote schema/history/deadlines. No remote retirement or decommissioning claim will be made.

## Additional local preflight and maintenance boundary

Read-only inspection after Render exclusion found Dashboard schema_version=2, revision=5 and six saved Widgets: overview, board, deploy, links, journal and milestone, all with selection.kind=all. No broken Project references were found. V16 classification columns still exist and Link.project_id/Project.category_id are present. These are partial structural checks, not complete Flyway validation or V18 rehearsal on a restored copy.

At the observation instant there were zero other client connections to the local database. This does not fence IDE restarts or prove no future writer. Do not stop the IDE or require application downtime throughout investigation or isolated testing. Immediately before actual V17/V18 application, prevent old application writes/restarts for the maintenance interval; stop a running old backend only if present. PostgreSQL migration locks do not prevent legacy writes after commit. The old Dashboard v2 frontend can continue before cutover; after switching to the Widget backend it receives 410 until adapted. Frontend implementation remains outside scope. The user questioned the need for downtime; no migration or forced process shutdown is inferred from that question.

## Isolated contract validation

Re-ran CategoryCutoverMigrationTests and WidgetMigrationTests with Java 21, explicit final stage and the existing isolated test init script. Both suites passed: 14 tests, zero failures/errors/skips. See [test summary](migration-test-results.json). This verifies existing synthetic migration contracts; it does not complete Session 2 backup/restore rehearsal or authorize local migration. All 18 migration source hashes remain unchanged and documentation links resolve.

## 2026-09-22 inventory refresh

The user requested completion and confirmed that `backend-postgres-1` is the only local database, with no separate backend, automatic backend restart, or pending client creation request. Render remains excluded. The existing PostgreSQL container was stopped; it was started for read-only inspection, without starting an application or executing migrations. Restart policy is `no`, the existing named volume is retained, and port 5432 is loopback-only.

- Actual history is now successful V1-V18. The earlier V16 observation is historical; this execution did not perform V17/V18. Flyway `validateWithResult` over a PostgreSQL connection forced to `default_transaction_read_only=on` validated all 18 source checksums. All migration source SHA-256 values match the recorded baseline.
- The durable deadline and exact V17 legacy-expiry predicate have elapsed. No other DB client was connected. Classification columns are absent; Widget/Placement/mapping tables exist.
- Counts: one workspace, two Projects, three Categories, three Tasks, two Journals, one Milestone, zero Links, one saved Dashboard, six Widgets, six Placements, six mappings. Private aggregate table digests are retained in ignored `.gradle/plan0014/local-validation-before.txt` for final comparison; no bodies, credentials or user IDs are published.
- Adjacent frontend source `../frontdev/src/features/dashboard/apiStore.ts` uses Dashboard v3 and Widget v1 configuration/type/data endpoints. This is source compatibility evidence, not a claim of production or browser deployment validation.
- The preserved bridge JAR still matches SHA-256 `56087B56B6FA3EC717428E8A1B3D5C7849D49A179B1643F5D05B154E9AFE692A`. It remains recovery evidence only, never a valid V18 runtime. The current explicit-final JAR is retained before simplification.
- An obsolete one-time V16 initialization property remains in the ignored local `.env`; remove only that entry after final admission. Never print or publish its manifest.

Selected route: **already V18 -> read-only validation -> repository/runtime simplification**. No local SQL migration, business-data backup/export, restore, counter increment or mapping decision is necessary or authorized by this inventory. Recovery rehearsal uses synthetic data only. Future restoration of older backups still requires isolated stage-aware admission. User confirmation plus no active backend means no process shutdown is necessary; preserved bridge archives must not be launched against this DB. Session 2 now verifies failure/retry/backup restoration in disposable databases before implementation proceeds.
