# PLAN-0015 implementation and validation

The approved plan and accepted decisions 0005/0006 govern this implementation. Requirements were synchronized before runtime work. No existing production/development database was migrated and no remote deployment was performed. Tests used isolated PostgreSQL databases and marked filesystem roots.

Final result: **320 Java tests passed, 0 failed, 0 skipped**; `test check bootJar` succeeded. Two Python inventory tests and the recorded filesystem/browser/container probes also passed.

## Implemented behavior

- V19 creates owner-neutral DocumentSpace, Document and Attachment persistence. V20 backfills Journal's unique non-null space FK while preserving original Journal fields and historical replay bytes. V21 creates deletion jobs without parent FKs.
- Nine APIs provide Journal-scoped creation/listing, global Document detail/save/publish/delete, multipart upload, authenticated binary streaming and TEMP deletion. Content remains opaque JSONB; ATTACHED rows define `attachmentIds`.
- LocalFileStorage uses generated relative keys, a mandatory marked root, bounded staging writes, exclusive publication claims and atomic moves. It refuses overwrite, linked paths and missing/wrong roots.
- Save, upload revalidation, cleanup claims and parent deletion follow workspace/parent/document/attachment lock order. Concurrent Document deletion rechecks existence after locking, so only one request succeeds and increments the counter.
- Parent deletion commits all keys before metadata removal. A bounded executor attempts cleanup after commit; durable jobs, leases and retry timestamps recover failures/restarts. TEMP direct deletion returns 200 only after binary and metadata removal.
- Open-in-View is disabled. Content queries finish their transactions before file access; bounded async streaming closes file handles on completion, abort and timeout.

## Evidence

| Area | Evidence |
| --- | --- |
| Initial guidelines, approval, storage feasibility | [Session 1](session-1.md) |
| JSONB, constraints and migration rollback | [Session 2](session-2-results.json) |
| Backfill, Journal lifecycle and access boundary | [Session 3](session-3-results.json) |
| Creation, pagination, detail and initial OpenAPI | [Session 4](session-4-results.json) |
| Full regression and packaging | [Full suite](full-suite-results.json), [affected suites](final-affected-results.json), [artifact](artifact.json) |
| Real multipart 10 MiB boundary and 11 MiB request cap | [HTTP upload limits](upload-http-limits.json) |
| Root failure and success-response semantics | [HTTP fault results](storage-fault-http.json) |
| UID 10001 named-volume restart and pending work | [Named-volume recovery](named-volume-recovery.json) |
| Paired DB/root restore, changed root and bind mount | [Bind restore](bind-restore-recovery.json) |
| Windows default sharing and exclusive-handle close/retry | [Windows file sharing](windows-open-file.json) |
| Actual Linux disk full, access denial, readonly, EXDEV and symlink | [Filesystem faults](linux-storage-faults.json) |
| Real Chrome, Swagger controls and full HTTP lifecycle | [Browser result](browser-result.json) |
| Bounded streams, real client abort and request recovery | [Wire results](stream-http-results.json), AttachmentStreamingTests in the full suite |
| Indexed bounded queries on 1,000 fixture rows | [Query plans](query-plans.txt) |
| Reviewed orphan inventory with live-key protection | [Dry-run](orphan-dry-run.json) |

Attachment tests exercise confirmed rollback compensation, real DB flush rejection, compensation failure recording, committed/unknown acknowledgements, upload-time parent deletion, strict cleanup cutoff, bounded batches, save/claim winners, lease fencing, file-delete/DB-finalization failure and parent rollback. Streaming tests hold 16 actual file streams open, assert zero borrowed Hikari connections, reject stream 17 with 503, and invoke the servlet timeout callback to verify the input closes.

## Corrections found during validation

The initial full regression run exposed two old V18-only expectations and a security test that depended on the developer's `.env` Origin. The tests now target the current schema and an explicit test Origin. The external developer configuration was preserved.

Slow-stream validation exposed pooled connections held by default Open-in-View; disabling it fixes this resource retention. Parent cleanup was moved out of the committing thread so file I/O cannot keep that thread's database connection. A concurrent-delete review added existence revalidation under the workspace lock.

The timeout test originally raced MockMvc's worker-populated result latch against the container callback; it now explicitly completes the simulated container lifecycle and verifies that the next stream succeeds.

The Swagger harness initially executed before debounced inputs were committed. It now waits for rendering and uses native browser input. A long-running classpath test JVM also saw classes change while Gradle recompiled; final browser validation uses an immutable packaged JAR in a container instead.

## Practical limits

Docker Desktop can buffer an entire 10 MiB response before a paused client consumes it. Wire tests establish readability after connection abort; deterministic slow-file tests establish the active-stream bound and timeout cleanup. PostgreSQL query observation alone does not prove that a pool connection was returned, so the controlled stream test also asserts Hikari's borrowed-connection count is zero.

An unknown upload commit never authorizes immediate file removal. If no durable recovery record can be obtained, files remain for writer-quiesced database reconciliation. The [operational procedure and read-only inventory tool](../../operations/document-storage.md) make this limit explicit. This implementation does not claim simultaneous PostgreSQL/filesystem durability across OS or power failure.

Container validation loads a separate test-only login fixture through PropertiesLauncher. That fixture, test configuration, local credentials and test storage are excluded from the production bootJar. Only dedicated validation containers/processes are stopped; named test volumes are retained. Local verification does not establish Mini PC or external deployment success.
