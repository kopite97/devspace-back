# DECISION-0005: Owner-neutral DocumentSpace access

Date: 2026-09-23
Status: `accepted`

Approved explicitly by the user on 2026-09-23 after review of both proposed records.

## Context

[PLAN-0015](../plans/PLAN-0015-document-editor-phase-one.md) requires generic Document/DocumentSpace models without Journal imports, ownerType/ownerId or duplicated workspace ownership. HTTP detail and Attachment APIs must still resolve the current Journal/Project ownership, including concurrent Journal movement and deletion.

The prescribed Decision template is absent; this record follows the existing Context/Decision/Rationale/Consequences/Validation format.

## Decision

- Define DocumentSpaceAccess in documentspace.application as the capability for resolving authenticated read, mutation and internal maintenance access to a Space.
- Implement the sole provider in Journal infrastructure. Return only the owned workspace and lock context, never Journal types or identifiers to generic Document/Space code.
- Journal presentation/application owns nested collection entrypoints. Document detail and Attachment access resolve through the same capability; authorId/createdBy are audit, not ownership.
- Lock workspace, ordered Projects, Journal, Space, ordered Documents, ordered Attachments, then deletion jobs. Revalidate discovery after locks. Reads use one REPEATABLE_READ snapshot. Storage I/O remains outside DB transactions.
- Preserve existing archived Project, disabled User, foreign/missing 404, Journal revision and replay policies. Maintenance is an explicit internal capability and may clean disabled users' expired TEMP files.

## Rationale

The boundary protects generic Document behavior from its current owner domain without a polymorphic owner registry, generic service interface layer or future provider implementations. Journal retains responsibility for its ownership rules.

## Consequences

Journal creation/deletion coordinates Space lifecycle; Document models remain owner-neutral. Concrete cross-feature dependencies must remain acyclic. No Agent Guide rule is added by this record.

## Validation

PLAN-0015 Sessions 3–9 cover two-user isolation, Journal movement/deletion races, archived/disabled access, lock ordering and absence of Journal imports/SQL in Document/Space.
