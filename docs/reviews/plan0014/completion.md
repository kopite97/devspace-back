# PLAN-0014 execution and validation

Date: 2026-09-22

## Scope and actual route

The user resumed the approved plan and confirmed one local Compose database, no separate/restarting backend and no pending creation requests. Render remains excluded. [Refreshed inventory](session-1.md) supersedes the historical V16 observation: the actual local database already had successful V1-V18 before this execution.

The admitted route was V18 validation, not V17/V18 application. No local business data was exported, restored, migrated or changed. No backup was needed for a schema operation because no such operation occurred. Protected actual-backup restoration remains a prerequisite for any future destructive recovery; synthetic restoration does not certify a real backup.

## Session evidence

| Session | Result |
| --- | --- |
| 1: inventory | Real read-only inspection, 18 Flyway checksums validated, original SQL SHA-256 values preserved, durable/legacy deadlines elapsed, no other DB connection, six Widgets/Placements/mappings. User confirmed the writer inventory. Frontend source uses Dashboard v3/Widget v1. |
| 2: rehearsal | 17 targeted migration tests passed before simplification. Fresh install, waiting/eligible V16, V17 and V18; expired-row cleanup cannot bypass the durable gate; committed V17 survives failing V18 and unchanged V18 retries successfully. Real pg_dump/pg_restore on synthetic V18 data followed by final application startup preserves every restored table. |
| 3: convergence | Already-final route: Flyway validation and final application/Hibernate startup through a read-only local connection. Existing one layout and all six Widget payloads read successfully. All 23 table counts and content digests, including Flyway/replay/counters, match before/after. No migration/backup approval package or writer shutdown was necessary. Authenticated mutation/replay smoke uses isolated data, preserving the real workspace. |
| 4: implementation | Removed Gradle/Docker stage selection and configurable historical HTTP serving. Incremental and clean JARs contain exactly V1-V18 with unchanged hashes and no obsolete generated property or seven bridge-only classes. Former bridge helpers are test-only, with explicit import only in the historical concurrency test. Normal replay readers/namespaces are unchanged. |
| 5: local handoff | No-stage Gradle help/classes/check/bootJar/bootRun and Docker image startup verified. Obsolete one-time V16 .env entry removed; other settings preserved. Current commands, release decision and restore instructions updated. No remote deployment/push or persistent local application service was introduced. |

## Automated and runtime results

- [Full suite](full-suite-results.json): **272 tests, zero failures/errors/skips**, using Java 21, PostgreSQL 16.4 Testcontainers and isolated configuration. `clean check bootJar` succeeded without a stage option.
- [Backup restoration](backup-restore.txt): custom PostgreSQL dump, separate restore database, all-row equality, no-op Flyway migration, actual application startup and Hibernate validation. This is synthetic fixture evidence only.
- [Clean JAR](clean-artifact.json) and [Docker JAR](docker-artifact.json) have identical SHA-256 `A76293417E1615A04BC25B512228A139832A17F3B02ED44A3439BD2E5968A4F9`. The incremental pre-clean JAR matched too. [Inspection script](inspect-artifact.ps1) checks all migration bytes, uniqueness and removal of bridge-serving content.
- Docker built without a build argument as `devspace-plan0014:local`; its isolated empty PostgreSQL received V1-V18 and passed Hibernate validation. A subsequent ordinary Gradle bootRun against that disposable V18 DB validated 18 migrations and performed no migration. Both servers returned 401/no-store for unauthenticated Widget requests.
- [Browser evidence](browser-result.json): all ten Widget/Layout operations render, Swagger Execute returns a layout, nullable/discriminator schemas are correct, and authenticated create/attach/data/in-use conflict/detach/delete/replay/404/retired-Dashboard410 checks pass.
- Final retirement tests deliberately set the obsolete replay property to **true**: every retired resource still returns 410 without writes. They retain missing-header/body 400, unauthenticated 401, CSRF403, disabled-account403, no-store, normal v1 auth/Category/Widget endpoints, v2 canonical-key conflicts, historical row bytes/expiry and golden fingerprints.
- No IDE GUI was driven. The available alternative is actual no-stage Gradle classes plus a direct Java runtime-classpath application startup and ordinary bootRun; local IDE metadata selects Java 21. This verifies the original missing-stage failure and actual startup path, not IDE UI interactions.

## Reproduction

Run from the backend root with Java 21 and Docker available:

```powershell
.\gradlew.bat -I docs/reviews/plan0014/isolated-tests.init.gradle clean check bootJar
& docs/reviews/plan0014/inspect-artifact.ps1 -Jar build/libs/devspace-0.0.1-SNAPSHOT.jar
docker build -t devspace-plan0014:local .
```

The test init script selects `widget-isolated-test.yml` so tests do not import the developer's `.env`. The migration tests contain synthetic clock changes only; real deadlines and replay expiries were never changed.

For an explicitly authorized local read-only inventory, [SQL inspection](local-inspection.sql) and [Java validation](LocalValidation.java) return metadata/aggregate digests only. Prepare the Java classpath with `plan0014Classpath` using the same init script. The Java helper restricts the target to the inventoried loopback port and sets PostgreSQL default_transaction_read_only; its optional `--boot` reads existing layouts/payloads and closes the application. Keep outputs in ignored `.gradle/plan0014/`, not shared documents.

## Recovery and limitations

The explicit-final pre-change JAR is archived locally at `.gradle/plan0014/explicit-final-before.jar`, SHA-256 `EAE2A786F634E54C0F4CC987683C8859AC35D6A53C60BC29DC70F920009C494E`. The pre-Widget bridge remains at the path/digest in [inventory](session-1.md). These local archives are not durable off-machine backups. Reverting this source change can use the former explicit-final application against V18; a bridge application is never a V18 rollback target.

The obsolete V16 initialization entry is preserved only in ignored `.gradle/plan0014/retired-v16-init.properties`; do not reapply it to V18 or another database. Restore old backups in isolation using the documented stage-aware route, then verify retention, mapping and schema admission before normal startup. After Widget writes, legacy JSONB is not a current backup.

Render, production credentials, production OIDC login, live backup recovery and frontend browser deployment were not inspected or changed. No shared agent guide, dependency, migration SQL, canonical replay namespace or business contract was changed.

Validation JVMs and the dedicated Chrome were stopped. The two labelled Docker validation containers and their disposable volume were removed. The existing local PostgreSQL is stopped, as it was at the initial inventory, and its named volume is preserved. No IDE or unrelated process was stopped. Start local PostgreSQL normally before the next development run.
