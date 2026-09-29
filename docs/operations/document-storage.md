# Document storage

PLAN-0015 uses a database plus a separately persisted local filesystem. Back up and restore both together. `object_key` is relative to the configured root; changing `FILE_STORAGE_ROOT` does not move files.

Set an absolute `FILE_STORAGE_ROOT`. Provision that directory before starting the application, with a regular file named `.devspace-document-storage` containing `devspace-document-storage-v1`. The application refuses a missing or incorrect marker, inaccessible root, or linked root instead of creating a fallback directory. Only the application account and trusted operators may modify the root. Never use a repository/build/image temporary directory for deployed data.

Set `ATTACHMENT_MAX_BYTES=10485760`, `ATTACHMENT_REQUEST_MAX_BYTES=11534336`, and `DOCUMENT_CONTENT_MAX_BYTES=1048576` unless intentionally changing limits. The request limit includes multipart overhead. Keep the reverse proxy request limit consistent. Public clients receive neither the root nor an absolute path.

The default cleanup grace is 86,400 seconds, fixed delay is 600,000 ms, and batch size is 100. Configure `ATTACHMENT_TEMP_GRACE_SECONDS`, `ATTACHMENT_CLEANUP_DELAY_MS`, and `ATTACHMENT_CLEANUP_BATCH_SIZE` when changing these values. `ATTACHMENT_CLEANUP_ENABLED` controls the scheduler. Two workers can share the database and root; each job has a five-minute lease token and failed attempts retry after ten minutes without being discarded. Parent deletion also submits a bounded immediate attempt to a one-thread executor with a one-item queue; rejected submissions remain durable for the scheduler.

Streaming uses 16 KiB buffers, at most `ATTACHMENT_STREAM_MAX_CONCURRENT=16` admitted streams and `ATTACHMENT_STREAM_TIMEOUT_MS=30000`. Keep `spring.jpa.open-in-view=false`: metadata transactions must release pooled database connections before file I/O. A content response includes `X-Request-ID`; timeout/interruption logs use that identifier and the attachment ID. A failure after binary transmission starts cannot reliably become a JSON error response.

## Docker persistent mounts

The image runs as UID/GID `10001`. Provision a named volume (for example `devspace-document-storage`) or an explicit host directory owned by that account. Create the marker in the mounted root before the application starts. Mount it at `/var/lib/devspace/document-storage` and set `FILE_STORAGE_ROOT=/var/lib/devspace/document-storage`.

Named-volume argument: `--mount type=volume,source=devspace-document-storage,target=/var/lib/devspace/document-storage`.

Bind-mount argument: `--mount type=bind,source=/srv/devspace/document-storage,target=/var/lib/devspace/document-storage`.

Use an explicit existing source directory for the bind mount. Do not remove volumes when recreating containers. A replacement container must use the same database and mounted root. An empty volume or wrong bind source fails startup because the marker is missing. Read-only mounts cannot serve as writable storage. Do not run the application as root to hide ownership errors.

## Failure semantics and reconciliation

Upload success means the file was published and TEMP metadata committed. A timeout can still follow a successful commit; retry can create another attachment. Confirmed transaction rollback triggers file compensation. Failed compensation records an `UPLOAD_ROLLBACK` deletion intent. Unknown transaction outcome preserves the file. An upload log with `attachmentId` identifies cases requiring reconciliation when the outcome or durable recovery record could not be obtained.

Before manually reclaiming any unmatched file, stop **all** application instances, schedulers and other storage writers, and wait for all original database transactions to terminate. Take a consistent database and filesystem backup. Produce a dry-run inventory of final object keys, `attachments.object_key`, `attachment_object_deletions.object_key`, staging files and hidden claim directories. Never remove a key present in attachment metadata. Existing deletion intents must be retried through the worker. Review unmatched files and abandoned staging/claims against logs and the backup before approving deletion. Traverse only the marked root without following links; exclude the marker and reject paths outside the root. An unavailable database or unconfirmed writer termination makes the inventory insufficient for deletion.

Files in `staging` are private and are never exposed by content endpoints. Upload publishes with an atomic move on the same filesystem. An unsupported atomic move fails the operation; there is no non-atomic copy fallback. A claim directory prevents concurrent uploads from overwriting an existing key. Failed uploads normally remove staging/claims; a process crash can leave these for the quiesced reconciliation above.

Restoration must preserve the root marker, relative keys and account permissions. Start with writers stopped, restore the matching database/root pair, check pending deletion intents, then resume. Validate an existing content URL and cleanup retry before reopening writes.

## Read-only orphan tool

After stopping writers and confirming original transactions have ended, export this query from the matching database to a UTF-8 JSON file:

```sql
SELECT json_build_object(
  'live', (SELECT coalesce(json_agg(object_key), '[]') FROM attachments),
  'pending', (SELECT coalesce(json_agg(object_key), '[]') FROM attachment_object_deletions)
);
```

Run `python tools/document-storage-inventory.py --root <absolute-root> --database-inventory <export.json> --writers-stopped` from the repository. [The tool](../../tools/document-storage-inventory.py) only reports candidates. It never deletes files, follows links, or treats a missing database export as an empty database. `--writers-stopped` records the operator's confirmation; it does not stop processes or prove quiescence. Review `missingReferencedKeys` before considering candidate removal. A stale inventory must not be reused after writes resume.

Monitor `attachment_object_deletions` using `reason`, `attempts`, `next_attempt_at`, `lease_until` and the job ID in retry logs. A growing count, repeatedly increasing attempts or overdue leases requires investigating mount availability, permissions and database health. Do not clear this table to silence errors: it may be the only surviving copy of a deleted parent's object keys.
