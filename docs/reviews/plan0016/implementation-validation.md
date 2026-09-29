# PLAN-0016 implementation validation

Date: 2026-09-23

Document responses now include saved attachment metadata. The existing nine operations and input whitelist remain unchanged. No production dependency, schema migration, storage implementation, or actual development database change was needed.

## Implementation

- A bounded ATTACHED query reads at most 101 rows. The response rejects overflow and pending deletion claims instead of truncating or returning partial success.
- DocumentSnapshotReader derives attachmentIds and attachments from the same metadata rows. Ownership is established by the existing DocumentSpaceAccess before reading. GET retains REPEATABLE_READ; commands retain existing workspace/parent locks and transaction boundaries.
- Claims are checked in one batch. AttachmentRegistryTests compares Hibernate prepared statement counts for one and 100 saved attachments and asserts equality.
- PUT validates all attachment membership before deletion conflicts. Missing or other-Document IDs return 404 regardless of input/UUID ordering; format/duplicate/count failures remain 400. Failed writes leave metadata and the workspace counter unchanged.
- OpenAPI distinguishes TEMP upload and ATTACHED restoration items, requires all eight metadata fields, limits the saved array to 100, and requires UUID deletedId in both delete responses.
- Stored invariant failures use the standard 500 envelope and log its requestId plus document identity and a fixed reason; no file paths or bodies are logged.

## Verification

- [Full suite](full-suite-results.json): 326 tests across 79 suites, zero failures/errors/skips; `test check bootJar` passed with Java 21. The recorded JAR hash identifies the tested production artifact.
- [Final registry assertions](final-registry-tests.json): six tests passed again after adding explicit POST/PUT response-only field rejection, 101-ID/malformed UUID, unauthenticated/disabled session, and archived Project checks. Production code and JAR hash are unchanged after the full suite.
- AttachmentRegistryTests covers empty/mutation responses, 100 saved plus extra TEMP/claimed TEMP, eight fields and ordering, constant query count, missing files/root failure, invalid stored data, ownership/error precedence, repeatable-read concurrent detach/parent deletion, and independent UTF-8 content/request byte boundaries.
- DocumentApiTests, DocumentMutationTests and DocumentOpenApiTests were updated for the additive response and corrected 404 contract. Existing lifecycle, upload, streaming, archive/disabled access, and cleanup tests remain part of the full suite.
- [Chrome restoration](browser-restoration.json): real multipart uploads, 100 saved files, browser reload discarding in-memory registry, GET-only reconstruction, all 100 binaries readable, extra TEMP excluded, publication/detach/direct deletion/parent deletion, and Swagger/schema checks passed.
- Default real HTTP limits: 10 MiB file accepted, 10 MiB + 1 byte rejected with 413; empty and unsupported file types rejected with 400.
- [Multipart boundary](multipart-boundary.json): a separate isolated server configured a 1024-byte whole-request limit with the normal larger file limit. A structurally valid single-file multipart request at 1024 bytes succeeded and 1025 bytes returned 413. No operational limit was changed.
- Two bootTestRun servers used fresh PostgreSQL Testcontainers and temporary marked storage roots, not localhost:5432/devspace. Both validation servers and their dedicated Chrome profile were stopped after verification.

## Scope and limits

The browser used a minimal test harness, not a product frontend editor. It proves the HTTP restoration contract after reload; frontend UI implementation remains separate. TEMP-only unsaved recovery, binary repair, and metadata existence after a later delete are not promised. The two requirements documents and their response examples reflect this extension; [frontend handoff](frontend-handoff.md) describes integration and failure handling.
