# DECISION-0004: Independent Widgets and Dashboard Placements

Date: 2026-09-16
Status: `accepted`

## Context

Widgets currently live in a HomeDashboard JSONB array, sharing configuration, placement, and conflict boundaries. The user wants a foundation for GitHub, server metrics, Kubernetes, Jenkins, and database logs, and permits redesigned responses with separate frontend adoption. Backend implementation and isolated validation were explicitly approved on 2026-09-16; production and frontend work remain excluded.

The prescribed Decision template is absent, so this record follows the existing Context/Decision/Rationale/Consequences/Validation format. Details belong in [PLAN-0013](../plans/PLAN-0013-independent-widget-refactoring.md).

## Decision

- Introduce a workspace-owned Widget aggregate for type, title, versioned configuration, and editing lifecycle. Dashboard owns placement order/size. Separate Widget revision from layoutRevision.
- Source features own credentials, connections, collection, and synchronization. Widget types use code-registered definitions, validation, and data handlers; no dynamically executable plugins.
- Store common fields relationally and validated configuration as JSONB. Enforce workspace boundaries with application checks and composite references.
- Separate configuration/layout from display data. Use common observation metadata and typed payloads; workspace revision does not prove external freshness.
- Initially migrate existing types and validate extension boundaries. Exclude external integrations, collectors, sharing, and SSE.
- Use versioned contracts and coordinated cutover. Old full Dashboard writes must not overwrite independently edited Widgets.
- Following approved PLAN-0014 local admission, Widget release artifacts unconditionally package the final schema. Gradle, IDE and Docker workflows require no stage selection; no runtime property can restore legacy HTTP replay. Startup remains subject to the documented DB admission procedure. Preserve previous bridge artifact/source as archive-only recovery evidence for isolated upgrades of old backups; never run it against V18. Keep V17 drain checks, Flyway history and JPA validation unchanged; do not introduce conditional entity scanning or replace JPA to support bridge in the Widget release. Local admission does not certify Render, which was excluded from PLAN-0014 operations.

## Rationale

Configuration and layout have different editing boundaries. An independent Widget lifecycle avoids expanding Dashboard and nullable shared fields for each integration. Source collection evolves independently from presentation settings.

## Consequences

Relational migration, type-specific validation, independent APIs, and frontend adoption are required. Preserve old string-ID mappings. Define deletion constraints, initialization, configuration versions, observations, and layout revisions explicitly.

Initially allow one placement per Widget. Sharing needs a later contract. Unplaced Widgets remain valid and discoverable. Migration must not discard saved configurations.

Preserve DECISION-0002 Category identity and missing-reference rules. This updates its Dashboard contract through PLAN-0013 without changing unrelated APIs. It does not resume DECISION-0003's rejected implementation plan or introduce Redis. Agent-guide changes require separate approval.

## Validation

Validate lossless migration, workspace isolation, independent conflicts/rollback, type extensibility, snapshot/time semantics, creation replay, legacy retirement, OpenAPI, frontend handoff, and recovery limits through PLAN-0013.
