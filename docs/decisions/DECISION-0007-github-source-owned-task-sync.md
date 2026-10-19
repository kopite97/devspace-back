# DECISION-0007: GitHub source-owned Task synchronization

Date: 2026-10-19
Status: `accepted`

Approval: 2026-10-19 user explicitly approved PLAN-0017 and requested execution. This record was created as proposed, then accepted based on that existing approval; it does not expand the approved design.

## Context

The user approved PLAN-0017 after explicitly selecting multiple repositories per project, one GitHub account per user, condition-transition importing, no linking of existing Tasks, and optional synchronization pause.
The decision template is absent; this record follows existing Context/Decision/Consequences sections.

## Decision

- Service sessions remain independent of GitHub App identity. User tokens prove user access; installation tokens collect source data; App JWT serves installation issuance and delivery recovery.
- GitHub owns credentials, access proofs, source snapshots, durable inbox/jobs and import exclusions. Task owns local invariants and exposes an inward application boundary for guarded source changes.
- A source-backed Task has exactly one Issue; one Issue can produce one Task per project. Title/body/status follow the source; priority/tag remain local. Ordinary Task linking is unsupported.
- PostgreSQL persists jobs and leases. External requests occur outside DB transactions; generations fence late results. No new messaging infrastructure is introduced.
- Private source access applies to every Task reading/aggregation/replay path. Access proof has bounded freshness; detached local copies and historical browser responses cannot be revoked remotely.
- Pause preserves source links and security checks; disconnect converts in bounded batches with explicit partial completion.

## Consequences

Changes require new schema, authentication adapters, Task access filtering and integration tests. Existing widget ownership and document access decisions remain unchanged. No recurring agent guide changes are introduced.
This record captures the design already authorized by PLAN-0017; no additional framework, provider or permission scope is approved by it.
