# ProjectCategory-only staged rollout

Implementation runbook for accepted DECISION-0002 / PLAN-0011. No production migration is authorized by this file.

## Approved mapping manifest

The cutover accepts an explicit JSON manifest, never a name/scope-derived mapping. Before execution, review a snapshot of workspace IDs, resource IDs, revisions and stored widget JSON. The manifest contains `workspaces`, each with `workspaceId`, `expectedDataRevision`, optional `dashboard` (`expectedRevision`, `expectedWidgetsMd5`, `selections` keyed by widget ID), and `links` (`id`, `expectedRevision`, `projectId` nullable). The widgets digest is PostgreSQL `md5(widgets::text)` of the reviewed JSONB value, not a browser serialization. MD5 is a change precondition, not a security signature.

Every existing workspace must be listed, including those with no stored Dashboard or Links. `dashboard` must be present exactly when a stored Dashboard exists. Every existing Link must appear once; null means the explicitly approved unlinked policy. Missing, duplicate, foreign or changed rows fail the entire transaction. Every ambiguous scope-only widget must have a reviewed selector. Unambiguous projectId/all widgets preserve their identity; a supplied conflicting selector is rejected. Category IDs never come from names. All revision/counter capacity and ownership checks run before mutation.

The manifest is supplied through a deployment-controlled PostgreSQL session setting for the cutover migration; it is not exposed as a business HTTP endpoint. A committed manifest containing real user IDs/data is not required. Store production exports and approvals in the normal protected deployment location.

## Release boundaries

- Expand: V1–V15 only; still run the previous v1-compatible artifact. V15 is additive and creates no Category/Project assignment.
- Cutover: drain old writers; review and supply manifest; run V16 with the compatible v2/legacy-replay artifact. v1 becomes replay-only; canonical persisted creation namespaces stay unchanged. Record last v1 commit T and the latest historical replay expiry.
- Contract: only after all legacy replay retention and clock margin have elapsed, run V17 and use the final artifact. Never ship future contract migrations in an earlier-stage artifact or repair old migrations.

Pending client creates retain their original URL/body/key. A v1 key sent to v2 is a conflict, not a new creation. Stop old writers before enabling v2. After cutover, fix forward; restoring a backup can lose later business writes and replay rows and requires separate incident approval. Do not reconstruct old scope from Category names/IDs.

The target production cutover time, protected backup retention and clock margin remain deployment approvals.

### Packaging and drain preflight

**Current PLAN-0014 release policy:** the current source unconditionally packages V1-V18. Use `./gradlew.bat bootJar` or `docker build -t devspace-backend .`; no stage argument is required. Legacy HTTP replay is unconditionally retired. Artifact creation does not authorize startup against a database that has not satisfied V16/V17 prerequisites. Local V18 admission was verified separately; Render remains excluded and unverified.

The bridge commands below apply only to the preserved pre-Widget source, commit `a80fec5781943c34adc6457213d4c366009013d1`, or a separately reviewed maintenance checkout based on it. Preserve the actual deployed artifact/digest and its source in release storage. A local reconstruction is recorded in [PLAN-0013 build evidence](reviews/plan0013/build-policy.md); it is not proof of a production artifact or backup. Do not re-enable bridge mode on the Widget source to perform maintenance. No source tag, remote branch, or production artifact was changed by this policy update.

Build the bridge with Java 21 using `./gradlew.bat clean bootJar -PcategoryStage=bridge --no-daemon`; archive that immutable jar and its SHA-256. It contains V1–V16 and enables only historical v1 creation replays. The preceding expand step uses the previous compatible artifact and a deployment migration target of V15; do not run the bridge while old writers remain active.

For V16 startup, supply the protected, reviewed manifest on every Flyway connection via Spring Boot's **`spring.flyway.init-sqls[0]`** using `SELECT set_config('devspace.category_cutover_manifest', '<SQL-escaped manifest JSON>', false)`. Spring Boot 4.1.1 uses the plural list property `init-sqls`; the singular `init-sql` is not its configuration key. Use an indexed element so commas in SQL/JSON are not treated as list separators. Pass it from the deployment secret/config mechanism, not a committed file or command history. Optionally set `devspace.category_cutover_clock_margin_seconds` in a second initialization statement (default 300). Validate on a restored isolated backup first. A missing or stale manifest aborts the transaction and startup. Do not repair the migration to bypass it.

For local startup, this application imports the root `.env` as Java properties. A reviewed one-time `spring.flyway.init-sqls[0]=SELECT set_config(...)` entry can therefore be placed directly in the ignored `.env`; no new environment-variable placeholder is needed. Generate it from the actual local workspace revision, Link IDs and stored Dashboard revision/digest. Do not use an empty manifest for a populated database or invent scope-to-Category mappings. Remove the entry after V16 succeeds. If the data changes before startup, inspect and regenerate the preconditions rather than bypassing them. Existing `all` and Project selections are preserved by the migration; ambiguous scope-only selections still require explicit approval.

V16 records a durable `category_cutover_state` row after acquiring the cutover locks. Its `cutover_at` is a conservative T after the last old commit. `retire_after` is at least `max(T + 24 hours, existing replay expires_at) + clock margin` for a populated database, even if expired rows are later cleaned up. A fresh empty database needs no legacy window. Read this row for deployment scheduling; never shorten its deadline or rewrite replay expiries. Also confirm old instances, background jobs and queued writers are stopped and cannot restart. New clients must drain pending v1 creates on their original URL/body/key through the bridge, and refresh all v1 cursors and Dashboard drafts.

Build the current final jar with `./gradlew.bat clean bootJar --no-daemon`. It contains V1-V18 and permanently rejects historical v1 replay handling (410); the old replay property has no consumer. V17 refuses startup until the durable deadline and the latest remaining legacy replay expiry plus margin have elapsed. New v2 replay rows do not extend this window. Before rollout, verify the 18 unique migration entries/checksums and absence of bridge-serving classes and generated properties with [artifact inspection](reviews/plan0014/inspect-artifact.ps1). Retain the bridge archive for incident analysis and isolated, stage-aware restores only. The historical bridge commands above apply exclusively to the preserved pre-Widget source, never to the current application.

Rollback before V16 can restore the previous application while retaining additive V15. After V16, use a compatible bridge fix; an old writer cannot safely interpret migrated Dashboard/Link data. After V17, use a compatible final fix. Restoring the pre-cutover backup is a separately approved data-loss/reconciliation operation, not an automatic rollback. Expired historical replay bytes remain untouched by these migrations. No production cutover has been performed during implementation validation.

## Category query and cache handoff

Normal Project/Task/Journal/Milestone lists, Task stats and Overview accept `category=all|uncategorized|UUID` (omission means all). UUIDs are canonicalized for cursor binding; an empty, repeated, malformed or retired scope selector is a 400. Owned but mismatching projectId/Category intersections return empty results, while unknown/foreign identities return 404. Search, archive/trash/date/sort defaults remain resource-specific.

A normal read or new mutation returns `X-Workspace-Data-Revision` as a decimal string from the body transaction. Keep it as a string/BigInt, never a JavaScript Number. Creation replays omit it and return their original snapshot. On Project Category changes, refresh the affected Project and derived child lists/details/stats/Overview plus Category counts; Category rename refreshes the Category reference map. Resource revision is the edit-concurrency token, not derived display freshness. Do not increment child revisions or rewrite child rows to invalidate caches.

All four list cursors now start with v2 and bind the Category and the remaining query context. Old cursors are rejected. A cursor does not bind workspace dataRevision and does not promise a frozen multi-page result: after relevant mutations the client should restart at page one. Within each request, counts, rows, Category buckets and the freshness header share one repeatable-read snapshot.

GET /api/v2/projects/category-counts accepts no query parameters. It returns every owned Category in createdAt/id order (including zeros), followed by the null bucket, with separate active/archived counts and totals. Overview uses the same server aggregation, with its optional Project/Category intersection. No client needs to collect all Projects for Category filtering or sidebar counts.

## Dashboard and Link handoff

Dashboard GET/PUT moves to /api/v2/dashboards/home and schemaVersion 2. A widget carries required selection: {kind:all}, {kind:uncategorized}, {kind:project,projectId:UUID}, or {kind:category,categoryId:UUID}. Links supports all four selectors; deploy remains an all-only placeholder with no backend Deploy endpoint. Neither links nor deploy accepts limit. Top-level widget scope/projectId are rejected.

Widget responses add required selectionState (valid or missingCategory). Remove this output-only field before PUT. Missing Category UUIDs are retained; GET never repairs stored JSON or increments Dashboard revision. PUT can preserve the same missing selection only under its existing widget ID. New or changed missing selections return RESOURCE_NOT_FOUND. A recreated same-name Category does not restore the old UUID relation. Dashboard save still compares its explicit revision and increments it once, including unchanged saves.

Home's transient selection overrides the effective selection for that render only. No transient selection means use the stored one; transient all means all. Do not PUT these temporary changes. Project selection maps to projectId, Category selection to category=UUID, and unclassified to category=uncategorized. Category/Project filters intersect when a resource request explicitly supplies both.

Link responses now include nullable projectId/projectName/categoryId instead of scope. POST projectId omission/null leaves the Link unlinked; PATCH omission preserves, null clears and UUID assigns an owned active or archived Project. Unlinked Links and Links under uncategorized Projects match category=uncategorized. projectStatus=active/archived excludes unlinked Links. Global order and collectionRevision remain independent of derived Category membership; refresh using the workspace header when Project metadata changes. Reorder still submits every owned Link ID, not a filtered subset.
