# Widget backend cutover and recovery

This is the PLAN-0013 handoff procedure, not approval to perform production operations. Backend fixtures do not establish production readiness. See the [API contract](widget-api-contract.md), [plan](plans/PLAN-0013-independent-widget-refactoring.md), and [category transition procedure](project-category-only-rollout.md).

## Prerequisites and release artifact

- Obtain separate production approval and coordinate the frontend release. The existing frontend's Dashboard v2 contract receives 410 after this switch; it cannot use the new layout without adaptation.
- Determine the actual database stage, replay expiry, saved Dashboard count/size, and writer instances. Resolve V16 manifests and V17's legacy replay drain before V18. Never bypass the drain or apply V17 after V18.
- Preserve the pre-Widget bridge artifact/source as archive-only recovery evidence for isolated upgrades of old backups. Current builds use `./gradlew.bat bootJar` or `docker build -t devspace-backend .` without stage arguments. Keep Hibernate `validate` and immutable Flyway checksums.
- Inspect the final JAR: V1-V18, staged V16/V17 exactly once, no generated transition property or bridge-serving classes. Legacy HTTP replay is permanently retired. No new dependency or environment variable is needed. Use [artifact inspection](reviews/plan0014/inspect-artifact.ps1).
- Back up the database and verify a recovery procedure appropriate to its actual volume. SQL transaction rollback on disposable fixtures was tested; a production backup restore and its duration were not.

## Expand and backfill/switch

V18 packages additive schema and backfill in one transactional Flyway migration. Stop and drain **all** legacy writers before starting the new application/Flyway; the lock within V18 does not permanently fence an old process after migration commits. Do not run old/new writers concurrently.

Before the approved migration, inventory legacy rows: schemaVersion 2, positive safe revision, valid Widget shapes/types/title/size/selector/limit, owned Project references, distinct legacy IDs, workspace counter capacity. Keep unresolved Category IDs; do not resolve them by name or broaden selection. Invalid data needs an explicitly reviewed correction, not silent omission. Include saved-empty and absent dashboards in the inventory.

The migration obtains table locks, verifies V17, creates Widget/Placement/mapping/replay tables, and converts saved arrays. It preserves legacy JSONB/revision/timestamps, assigns deterministic backfill IDs, copies layout revision, starts Widget revisions at 1 and increments the workspace counter once per saved dashboard, including saved-empty. Missing dashboards remain missing. Any failure rolls back schema/data changes together. Retry recomputes the same identities; collisions stop execution.

Reconcile **before enabling clients**:

- Saved Widget count = Placement count = mapping count = sum of legacy array lengths, per workspace/home.
- Match each legacy index to Placement.position, type/title/size/selection and optional limit. Match saved layout revision/timestamps and initial Widget timestamps to their source. Mapping IDs must match the frozen UUID vectors/algorithm.
- Saved-empty remains initialized with zero placements; absent remains uninitialized. Counter increments equal saved dashboard presence, not Widget count.
- Check composite ownership constraints, no dangling placements and unchanged V1-V17 checksums. There must be no unexpected replay rows or initialized defaults from reads.

Authenticated smoke checks: catalog, layout, every local payload, missing Category unavailable semantics, create/attach/edit/reorder/remove/delete, replay and conflict handling, no-store and paired revision headers. Confirm Dashboard v2 GET/PUT returns 410 with no writes. Verify compatible frontend behavior before ending the maintenance window; frontend implementation is outside this backend work.

## Recovery boundaries

Before commit, PostgreSQL transaction rollback removes partial V18 effects; fix the cause and retry the unchanged migration. The isolated rehearsal verified rollback after insertion and deterministic retry.

After commit but before new writes, preserved JSONB/mappings are reconciliation/recovery inputs, not a guarantee that an old binary can run against V18 history. Use a separately verified V17 backup/application pair if restoration is chosen. Never delete Flyway history, repair checksums or edit an applied migration to force a downgrade. A pre-Widget bridge artifact is not a V17 rollback artifact.

After new writes, prefer a forward fix. Old JSONB is stale, and new unplaced Widgets/configuration edits may not be representable in the old model. Stop writes, export new configuration/layout/replay state and mapping, assess conversion/loss, and seek explicit approval before any destructive restore. Production restore time, tolerable downtime, volume ceiling and frontend rollback readiness remain operational decisions.

## Deferred contract phase

Do not delete legacy JSONB, legacy model history or mapping rows in this release. Destructive cleanup needs a separately approved migration/plan after retention and recovery requirements are settled. Expired replay cleanup remains per-key during a new operation; no scheduler or infrastructure was added.
