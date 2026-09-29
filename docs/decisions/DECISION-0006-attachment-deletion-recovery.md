# DECISION-0006: Durable Attachment deletion recovery

Date: 2026-09-23
Status: `accepted`

Approved explicitly by the user on 2026-09-23 after review of both proposed records.

## Context

[PLAN-0015](../plans/PLAN-0015-document-editor-phase-one.md) uses PostgreSQL metadata and LocalFileStorage binaries without a shared transaction. Save, TEMP cleanup and parent deletion must not lose deletion keys or remove a live ATTACHED file.

The prescribed Decision template is absent; this record follows the existing Context/Decision/Rationale/Consequences/Validation format.

## Decision

- Persist attachment-specific deletion intents/claims with objectKey, identity, reason and retry/lease information. This internal record has no cascading/restrictive FK to a deletable parent.
- Revalidate live ownership and TEMP state under ordered DB locks before claiming. A committed claim prevents reconnection; public status remains TEMP/ATTACHED.
- Delete files outside DB transactions, then complete metadata/job removal in a new transaction. Normal-root file absence is success; mount/permission/root failures require retry.
- Parent Hard Delete first persists all child deletion keys in the same transaction as metadata removal. Reuse existing claims. After commit, attempt bounded cleanup and retry durable unfinished work after failures/restarts.
- Parent 200 means DB deletion and durable cleanup scheduling, not immediate physical removal. TEMP direct-delete 200 requires file and metadata removal; storage failure returns 503 and leaves retry work.
- Upload rollback compensation deletes only after rollback is known. An ambiguous commit never authorizes immediate file removal. Unrecorded upload/staging orphans require a writer-quiesced, database-verified dry-run recovery procedure.
- Use existing Spring Scheduling with bounded batches/backoff/leases. Do not introduce an event bus, generic outbox, distributed transaction, Redis or storage SDK.

## Rationale

Durable work survives process termination and parent deletion while short claim transactions serialize Save versus deletion without holding DB locks during file I/O.

## Consequences

Binary removal may be delayed; the common API contract defines the success semantics. Failed jobs remain observable and are not automatically discarded. Upload without any durable metadata can leave an orphan, so this does not claim crash-safe upload or cross-system power-loss atomicity.

## Validation

PLAN-0015 Sessions 5–9 cover actual local files/PostgreSQL, rollback and ambiguous commit, competing Save/cleanup/delete, process restart, worker leases, parent deletion and orphan recovery dry-run.
