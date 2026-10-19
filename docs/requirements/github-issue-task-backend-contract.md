# GitHub Issue–Task Backend Contract

Date: 2026-10-19
Status: backend implemented and automatically verified; frontend handoff published and checked against OpenAPI. Live browser OAuth/Webhook validation remains open.
Source: approved [PLAN-0017](../plans/PLAN-0017-github-app-issue-task-integration.md).

## Decisions and defaults

- GitHub.com only. One GitHub numeric user ID per service user; many installations/repositories. Never replace an account in a callback.
- Session identity remains the service identity. OAuth state is random, hashed in DB, user/session-bound, single use, expires after 10 minutes. Fixed configured callback; no arbitrary return URL.
- Feature is disabled by default. Missing credentials while disabled must not break existing application startup. Enabled configuration fails closed when incomplete.
- GitHub API version: `2026-03-10`; JSON accept header. App JWT: installation-token issuance and webhook recovery. Installation token: collection. User token: identity and repository access proof.
- Repository permissions: Metadata read (implicit), Issues read. Events: issues, label, installation, installation_repositories, github_app_authorization; repository lifecycle as permitted by registration. No Contents/Actions write permission.
- Access checks use user installation/repository visibility plus Issues read capability; never trust installation ID alone. API denial/timeout must not produce an allow grant.
- Access grant TTL 60 seconds, HTTP connect timeout 3 seconds/request timeout 10 seconds. Requests coalesce per user/repository, no per-Task calls. Expired grants are not extended on failure.
- Project connection list is bounded to 100 active/disconnecting links; fully disconnected links disappear from the list. Reconnection reuses the original link identity after cleanup and resets settings, while preserving automatic-import exclusions. Request-side list refresh verifies at most one expired connection; individual connection reads revalidate that connection. Other expired entries remain explicitly pending. Repository verification stops starting additional pages after 10 seconds (a running request can take up to another 10 seconds).
- Browser redirects allow HTTPS or HTTP on exact loopback hosts only. The authenticated `/github/connection-result` page is a temporary frontend-free landing page; no secrets or source content are rendered.
- Query grant expiry uses one observation time per DB snapshot. Same SQL access predicate for detail/list/count/stats. Cursor is tied to workspace access-scope version; unavailable scope yields a new cursor requirement.
- A request-side coordinator performs network verification before transactional reads, including overview and widget entrypoints. Background refresh is supplementary, not a guarantee that all expired scopes are current.
- Matching: nonempty label sets require ALL labels; nonempty assignee sets require ANY assignee. Label and assignee groups are ANDed. Empty groups impose no restriction; empty settings match all issues. Use GitHub IDs, not names. Labels/assignees max 20 each.
- Only observed current matching matters. First enable/change/resume establishes an initial match baseline; already-matching issues require explicit selection. Later false→true matches import regardless of creation date. Exclusions dominate settings generations.
- A newly observed Issue after completed baseline can match immediately. During baseline, buffer/reconcile changes before activating automatic import; an incomplete baseline cannot auto-import all cached issues.
- The first baseline cannot complete before initial collection coverage is complete. Source observations received during a pending baseline are recorded in the source transaction, including IDs already passed by the baseline scan.
- Issue listing: limit 1..100/default 20; stable ID order and scoped cursor. Collector default page size is 100, with one page per 60-second work lease and durable continuation in the same logical job. Oversized responses restart the current stage with a smaller page size down to one. Initial cache collects all open issues and closed issues updated within 90 days; older closed issues use explicit asynchronous number lookup. Counts disclose collected coverage.
- Selected import max 100 issue IDs, asynchronous job, per-item results. Default Task priority normal/tag empty. Existing Task linking is unsupported. Import key bound to owner/project/link/action/body; same key/body replays, conflicting body returns 409; retention 24 hours.
- At most 10 queued selected-import jobs per link; further requests return 429 `GITHUB_IMPORT_QUEUE_FULL`, while valid idempotent replays remain available. A DB collection version, rather than wall-clock equality, fences the refresh after submission. Unrefreshed items fail with `GITHUB_SOURCE_NOT_FRESH` after 15 minutes.
- Label mapping IDs distinct; done optional. Closed/completed overrides labels, not_planned and unknown closure preserve prior state/todo fallback. Invalid mappings preserve only affected unresolved items; successful independent decisions proceed.
- Preserve current title/description on content overflow; keep state/labels current. First import overflow rejects item. Never silently truncate. Null body is empty. Max fetched response 4 MiB; webhook body 2 MiB; oversize is explicit failure and recoverable by bounded API reconciliation.
- Pause blocks Task writes/imports, not security or source collection. Resume requires current access and baseline preview. Project archive and user pause are separate reasons.
- Work claim lease 60 seconds, maximum 8 attempts, capped exponential backoff with jitter; respect Retry-After/reset even beyond cap. Workers batch 50, detach batch 50. API dispatch serialized per installation/credential; durable rate-limit state, no retry storm.
- Network collection/recovery use leases; local Task batches use one atomic short DB transaction after external verification and never hold a job lock while acquiring the workspace. A Task-worker pass handles at most 50 individual applications or one 50-item bulk batch, and stops starting more work after 10 seconds. Paused/unmanaged source rows do not generate unnecessary per-Issue application jobs. Explicit cleanup workers also run when provider integration is disabled, if workers are enabled.
- Manual sync cooldown 60 seconds per link; webhook delivery recovery every 10 minutes with persistent watermark/overlap, no whole-repository periodic polling. History gaps create explicit reconciliation jobs.
- Successful delivery payloads purged after 7 days, failure diagnostics after 30 days; tokens/raw upstream bodies never logged. Jobs and exclusion identities must not disappear while linked or explicitly excluded. Removal of final link schedules private cache cleanup.
- Token refresh uses per-account lease/version. Ambiguous consumed-token outcome requires reauthorization; no remote/local atomicity claim. Secrets use authenticated encryption with key ID, user/account AAD, separate deployment key.

## Implemented HTTP contract

All browser APIs require service session; mutations require existing CSRF/Origin checks. Responses no-store. Missing/foreign resources 404; invalid requests 400; stale revision/duplicate/conflict 409; throttling 429; unavailable provider 503. Common ApiError format remains.

| Method and route | Input / response |
| --- | --- |
| GET `/api/v2/integrations/github/account` | connection status, GitHub ID/login, reauthorization flag; never credentials |
| POST `/api/v2/integrations/github/authorization-requests` | 201 authorization URL and expiry; binds current service session |
| GET `/api/v2/integrations/github/callback` | code/state or denial; consumes state; fixed frontend redirect with safe outcome code |
| DELETE `/api/v2/integrations/github/account` | refuse active project links (409); invalidate local credentials after explicit cleanup |
| GET `/api/v2/integrations/github/installations` | page 1+, limit <=100; authorized installations and fixed App installation URL |
| GET `/api/v2/integrations/github/installations/{installationId}/repositories` | authorized paginated repositories |
| GET/POST `/api/v2/projects/{projectId}/github/repositories` | list / connect using installationId, repositoryId and Idempotency-Key; 201 link |
| GET `/api/v2/projects/{projectId}/github/repositories/{linkId}` | connection, revisions, access/collection/Task-sync status |
| DELETE same | revision and explicit disposition; 202 disconnection job |
| GET `.../{linkId}/issues` | state, label IDs, assignee IDs, cursor, limit; items,total,coverage,nextCursor,observation |
| GET `.../{linkId}/labels` | paginated labels with stable IDs |
| POST `.../{linkId}/task-import-previews` | selected IDs or current criteria; projected status/reasons, no mutation |
| POST `.../{linkId}/task-imports` | IDs, config revision, Idempotency-Key; 202 job |
| GET/PUT `.../{linkId}/import-settings` | revision, enabled, labelIds, assigneeIds; selected existing IDs handled by import API |
| GET/PUT `.../{linkId}/status-mapping` | revision, todoLabelId/doingLabelId/doneLabelId nullable; validation against repository |
| POST `.../{linkId}/status-mapping-previews` | candidate mapping; affected items/counts, no mutation |
| PATCH `.../{linkId}/sync-settings` | revision, paused boolean; 200 state or 202 resume baseline job |
| POST `.../{linkId}/sync-requests` | Idempotency-Key; 202 reconciliation job, cannot bypass pause |
| POST `.../{linkId}/issue-fetches` | issueNumber, Idempotency-Key; 202 bounded lookup including older closed Issues; shared manual cooldown |
| GET `.../{linkId}/sync-status` | latest collection and application status, times, coverage, safe error code |
| GET `/api/v2/integrations/github/jobs/{jobId}` | owner-scoped status, kind, counts, complete bounded per-item results (at most 100; no result cursor) |
| GET/DELETE `/api/v2/tasks/{taskId}/github-issue` | connection info / explicit local conversion with Task revision and current access |
| POST `.../{linkId}/cleanup-requests` | explicit destructive disposition + revision; service ownership, 202, no private content |
| POST `/api/integrations/github/webhooks` | raw signed payload; 202 after durable receipt, duplicate accepted, invalid signature 401 |

DTO state names: initializing/ready/paused/disconnecting/disconnected/failed for lifecycle; verified/check_pending/denied/reauthorization_required for access; idle/queued/running/partial/failed/completed for jobs. They are independent fields, not one overloaded status.
Task source metadata includes source type, allowed editable fields, link ID, GitHub ID/number/URL, source observation time, content error and status conflict. Plain Task retains prior response compatibility.
Scope omission metadata says external data unavailable without leaking hidden titles/counts. Restricted detail/replay returns 404. Foreign/inaccessible job IDs do not reveal progress.

Preview POSTs accept `limit` (default 20/max 100) and `afterIssueId`; the seek position is advisory, is not a signed cursor, and does not promise a cross-page snapshot. Access is rechecked on every page. Issue/label lists retain signed cursors; Task scope changes return 409 `TASK_ACCESS_SCOPE_CHANGED` and Issue scope changes return 409 `GITHUB_ACCESS_SCOPE_CHANGED`.

`import-settings`, `status-mapping` and `sync-settings` responses share `revision`, `enabled`, `generation`, `baselineReady`, nested `criteria`, nested `mapping`, and `paused`. Pause/resume returns 200 settings; resume's baseline/reconciliation remain asynchronous. A preview does not reserve source versions; import revalidates revision, access and refreshed source at apply time.

GitHub mutation responses do not carry `X-Workspace-Data-Revision`. Refetch affected Task/project/overview/widget APIs after jobs or settings changes. Those existing APIs retain their observation header. Source observation timestamps may change without Task revision or workspace business revision when no business value changed.

Repository DELETE requires query `revision` and `disposition=convert|purge`. Protected cleanup POST requires `{revision,disposition:"purge"}`. Both return 202 `{jobId}`. Detach job status exposes only progress/error metadata even after provider access is lost; no source items or content. Retry partial work with the latest link revision. Already converted local copies remain local; purge removes only remaining source-managed Tasks and cleans cached source data in bounded batches. Single Task conversion returns 204.

## Persistence and boundaries

- New migrations only. Account unique user FK; OAuth state hash/expiry/session binding; installation/repository link unique workspace/project/repository; access grant/version; issue and label snapshots; import baseline/exclusion identities; Task source link; inbox; jobs/claims/replays.
- Composite ownership references prevent cross-workspace Task/project/link assignments. One source per Task and one imported Task per project/Issue, including trash; local conversion retains exclusion identity.
- External data and credentials belong to github. Task owns mutation invariants. Task application access port is implemented by GitHub infrastructure, avoiding application-to-infrastructure imports and feature dependency cycles.
- Locks: workspace → ordered projects → Tasks → source/job rows as required; claim commits before any workspace acquisition. No worker holds a job lock while acquiring a workspace lock.
- Source generation and config revision checked at apply time. Workers do not overwrite local priority/tag and do not change layoutRevision.

## Verification and environment

Local Java 21 and Docker/Testcontainers PostgreSQL are available. Baseline 326 tests passed. A read-only live App JWT probe confirmed App/Client IDs, Issues read and one active installation; the local slug was corrected. API-reported events are empty and Webhook remains inactive. Browser OAuth/client-secret exchange, public delivery and organization scenarios are not verified; never print .env values during discovery.
Existing endpoint tests use Testcontainers PostgreSQL, MockMvc and Jackson 3. Use JDK HttpServer for upstream fault simulation without new dependencies. Run authentication/connection tests before Task import, and privacy tests in the same change as first source-backed Task storage.
This is the backend implementation contract. The post-validation [frontend handoff](frontend/github-app-issue-task-integration.md) describes the verified APIs, UI behavior, examples and remaining live-validation limits.

## Official references checked

- [User token authorization](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app)
- [User/installation repository visibility](https://docs.github.com/en/rest/apps/installations)
- [Issue endpoints and PR exclusion](https://docs.github.com/en/rest/issues/issues)
- [App JWT webhook recovery](https://docs.github.com/en/rest/apps/webhooks)
