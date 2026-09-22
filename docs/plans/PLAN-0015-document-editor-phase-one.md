# PLAN-0015: Document Editor Phase One

Status: `proposed`
Date: 2026-09-22

## Goal

Journal이 독점 소유하는 범용 DocumentSpace 안에서 Document를 작성·조회·저장·게시하고, LocalFileStorage에 저장한 첨부파일을 안전하게 연결·제거하는 1차 Backend 기능을 제공한다. BlockNote JSON은 해석하지 않고 PostgreSQL JSONB로 보존하며, 인증·인가와 Journal의 기존 동작을 유지한다.

이 문서는 [Runtime Template](PLAN-TEMPLATE-RUNTIME.md)에 따른 **구현 제안**이다. 현재 작업은 PLAN 작성까지만이며 구현, Migration 실행, 의존성 설치, 서비스 기동 및 환경 변경을 포함하지 않는다. 사용자 명시적 승인 전에는 `active`로 변경하거나 Session을 실행하지 않는다.

## Scope

- Journal → DocumentSpace → Document → Attachment 관계, Journal Context의 Document 생성/목록, 공용 Document 상세/PUT/삭제/Publish, Multipart Upload, Content Streaming, TEMP Cleanup을 포함한다.
- 현재 상위 연동 Domain은 Journal 하나다. DocumentSpace Public CRUD API와 Frontend 구현은 포함하지 않는다.
- PostgreSQL Metadata와 FileStorage Binary의 역할을 분리한다. 1차 구현체는 **LocalFileStorage**이며 저장 Root를 외부 설정으로 주입해 Local 및 향후 Mini PC에서 같은 Application Artifact를 사용한다.
- Docker 실행 시 첨부파일 Root는 persistent volume 또는 bind mount에 둔다. 컨테이너 임시 writable layer를 영속 저장소로 사용하지 않는다.
- SeaweedFS/S3-compatible Storage 연동은 제외한다. 추후 별도 검증 및 후속 PLAN에서 `S3FileStorage`를 추가할 수 있는 FileStorage 경계만 유지하며 구현체·설정·의존성·테스트를 미리 추가하지 않는다.
- Project/Milestone/Resource와 DocumentSpace 연결, Custom Block, GitHub/Server/Kubernetes API Block, 실시간 공동 편집, WebSocket Sync, Comment/Discussion, Mention, Template, Revision/History, Relation, Share Link, AI, Elasticsearch/OpenSearch는 제외한다. 이를 위한 Entity/Table/Service/API도 선행 추가하지 않는다.
- 기존 Journal의 body, 검색, Project 관계, revision, 생성 replay, Hard Delete 정책을 보존한다. Journal body를 Document로 자동 변환하거나 두 본문을 동기화하지 않는다.
- 현재 Working Tree의 기존 변경은 이 PLAN의 구현 실적으로 간주하지 않는다. 이번 수정에서는 이 PLAN 파일만 변경하며 요구사항·ADR·가이드·완료 PLAN 인덱스는 수정하지 않는다.

## Repository Review

### 확인한 지침과 선례

- [루트 AGENTS](../../AGENTS.md), [Plan Guide](AGENTS.md), [Implementation Guide](../agents/IMPLEMENTATION/AGENTS.md), [Decision Guide](../decisions/AGENTS.md)를 확인했다. 추가 경로별 AGENTS는 현재 저장소에서 발견되지 않았다.
- [Backend Requirements](../requirements/DOCUMENT_EDITOR_BACKEND_REQUIREMENTS.md)와 [Common Contract](../requirements/DOCUMENT_EDITOR_COMMON_CONTRACT.md)를 기준으로 한다. Storage 구현체는 이번 사용자의 LocalFileStorage 변경 지시를 우선한다. 두 문서의 기존 Storage 제품 선택은 이번 PLAN의 구현 전제가 아니며 Domain/API 계약은 유지한다. 아래 차이·명확화 항목이 원문에 반영되었다고 가정하거나 요구사항 파일을 이번 작업에서 수정하지 않는다.
- [Architecture](../agents/ARCHITECTURE.md), [Spring Boot](../agents/SPRING_BOOT.md), [API](../agents/API.md), [Database](../agents/DATABASE.md), [Flyway](../agents/FLYWAY.md), [Security](../agents/SECURITY.md), [Application](../agents/IMPLEMENTATION/APPLICATION.md), [Domain](../agents/IMPLEMENTATION/DOMAIN.md), [Ports and Adapters](../agents/IMPLEMENTATION/PORTS_ADAPTERS.md), [Design Principles](../agents/IMPLEMENTATION/DESIGN_PRINCIPLES.md)를 적용한다. Storage 전용 가이드는 없으며 FileStorage는 Ports and Adapters의 명시적 예시다. `TEST.md`에는 현재 별도 지침이 없다.
- 완료 선례: [PLAN-0006 Journal](PLAN-0006-journal-api.md)의 소유권·삭제·replay·PostgreSQL 테스트, [PLAN-0007 Milestone](PLAN-0007-milestone-api.md)의 상태 전이·Hard Delete, [PLAN-0008 Link](PLAN-0008-link-api.md)의 관계 변경·경쟁 검증, [PLAN-0009 Dashboard](PLAN-0009-dashboard-overview-api.md)의 JSONB·snapshot 조회, [PLAN-0013 Widget](PLAN-0013-independent-widget-refactoring.md)의 독립 Domain·JSONB·Migration·OpenAPI, [PLAN-0014](PLAN-0014-final-schema-and-bridge-retirement.md)의 최종 Schema 및 Artifact 검증을 참고한다. 과거 PLAN의 v1 URI보다 현재 Controller를 우선한다.
- [DECISION-0001](../decisions/DECISION-0001-project-category.md)은 superseded다. [DECISION-0002](../decisions/DECISION-0002-project-category-only-classification.md)의 현행 v2/소유권/응답 계약과 [DECISION-0004](../decisions/DECISION-0004-independent-widget-and-dashboard-placement.md)의 독립 Domain·최종 Migration 패키징을 보존한다. [DECISION-0003](../decisions/DECISION-0003-revision-keyed-query-cache.md)의 연결 구현 PLAN은 rejected이며 Redis를 재도입하지 않는다.
- 완료 PLAN 및 Runtime 코드에서 File Upload, FileStorage adapter, 운영 Scheduler 선례는 발견되지 않았다. 생성 replay 만료 정리는 생성 요청 중 수행하며 Scheduler 선례로 간주하지 않는다.

### 코드에서 확인한 기준선

| 분석 항목 | 현재 구현과 계획에 미치는 영향 |
| --- | --- |
| Runtime | [build.gradle](../../build.gradle): Spring Boot **4.1.1**, Java **21**, springdoc **3.1.0**, Spring MVC/JPA/Flyway/Validation/Security, Jackson 3의 `tools.jackson` 사용. |
| Package/Layer | `com.kopite.devspace.<feature>.presentation/application/domain/infrastructure`. JPA Entity가 Domain Model이며 scalar UUID 관계, Domain Repository와 Infrastructure JPA Adapter를 사용한다. |
| 작성 규칙 | Constructor injection, final 의존성, 제한된 Entity 생성자/factory, getter와 명시적 변경 메서드. Controller는 DTO/HTTP, Application은 orchestration/transaction, Domain은 invariant를 책임진다. |
| Journal Entity | [Journal.java](../../src/main/java/com/kopite/devspace/journal/domain/Journal.java): UUID id/workspaceId/projectId, title/body, entryDate, revision, createdAt/updatedAt. body는 필수 TEXT이며 Document 본문으로 대체할 수 없다. deletedAt이 없다. |
| Journal Command | [JournalCommandService](../../src/main/java/com/kopite/devspace/journal/application/command/JournalCommandService.java): 생성 시 active owned Project, 24시간 Idempotency-Key snapshot replay, workspace counter를 같은 Transaction에서 처리. 삭제는 revision 확인 후 물리 삭제한다. |
| Journal Query/API | [JournalQueryService](../../src/main/java/com/kopite/devspace/journal/application/query/JournalQueryService.java), [JournalController](../../src/main/java/com/kopite/devspace/journal/presentation/JournalController.java): `/api/v2/journals`, read-only REPEATABLE_READ 조회, `{items,total,nextCursor}`, 기본 limit 20/최대 100, signed keyset cursor. 기존 상세 수정은 PATCH다. |
| Journal 권한 | 인증 User의 Personal Workspace와 Journal의 현재 Project 소유권을 함께 확인한다. 타인/없음은 동일 404. archived Project의 기존 Journal 조회·편집·삭제는 허용한다. 새 Journal 생성/다른 archived Project로 이동만 금지한다. |
| Transaction/Lock | Application의 `@Transactional`; workspace → 정렬된 Project UUID → Journal 잠금. 조회 count/page/소유권은 같은 snapshot. 외부 Storage I/O를 이 Transaction 안에 넣지 않는다. |
| PostgreSQL/JSONB | [Widget](../../src/main/java/com/kopite/devspace/widget/domain/Widget.java)의 `@JdbcTypeCode(SqlTypes.JSON)` String config, HomeDashboard의 typed JSONB List 선례가 있다. Document에는 opaque JSON String 매핑을 우선 재사용한다. |
| Migration | [application.yml](../../src/main/resources/application.yml)의 `ddl-auto: validate`, Flyway SQL. 현재 최신 V18, V16/V17은 build.gradle에서 최종 Artifact에 함께 포함한다. 신규 번호는 실행 시 재확인한다. |
| Audit | 주입한 Clock과 UTC Instant, microsecond 정밀도, Domain에서 명시적으로 관리한다. JPA auditing 도입이나 timezone 변환을 추가하지 않는다. |
| Response/Error | [WorkspaceResponses](../../src/main/java/com/kopite/devspace/global/response/WorkspaceResponses.java)의 일반 DTO/no-store/관측 header, [ApiError](../../src/main/java/com/kopite/devspace/global/response/ApiError.java)의 `{code,message,fieldErrors,requestId}`, 중앙 [ApiExceptionHandler](../../src/main/java/com/kopite/devspace/global/exception/ApiExceptionHandler.java). blanket success envelope는 없다. |
| Validation/OpenAPI | strict request parsing + Bean Validation + Domain invariant. unknown/duplicate/null/type coercion 검증. `/v3/api-docs`, Swagger UI, sessionCookie/CSRF 명세와 공통 OpenAPI customizer가 존재한다. |
| Security | OAuth2/OIDC 로그인 뒤 서버 Session, CurrentUser 검증, disabled account/CSRF/Origin 정책. Document 전용 인증·JWT·익명 공유는 추가하지 않는다. |
| Local/Storage | [compose.yml](../../compose.yml)은 PostgreSQL **16.4**와 named volume만 정의한다. Spring Boot는 Local 실행 가능하며 `.env`를 외부 설정으로 읽는다. 첨부파일 Storage adapter/Upload/Scheduler 설정은 없다. |
| Tests | `src/test/java/com/kopite/devspace`의 JUnit 5, SpringBootTest, MockMvc, PostgreSQL Testcontainers, 실제 Transaction/동시성/fault injection. `src/test/browser`의 Node 브라우저 스크립트로 Swagger를 검증한다. |

## Proposed Contract and Approval Gates

다음은 검토 가능한 제안이며 새 Architecture를 이미 채택한 것으로 취급하지 않는다. Session 1에서 차이를 해소하고 승인된 계약을 고정한 뒤 구현한다. 승인된 방향이 달라지면 의존 Session과 검증 기준부터 수정한다.

| 항목 | 차이 또는 미정 사항 | 제안과 승인 기준 |
| --- | --- | --- |
| URI | 요구사항의 `/api/...`는 개념 경로이며 실제 Journal은 v2다. | `/api/v2/journals/{journalId}/documents`, `/api/v2/documents/{documentId}`, `/api/v2/attachments`로 통일한다. 기존 다른 feature의 버전은 변경하지 않는다. |
| ID | Common Contract의 예시는 숫자 ID지만 현재 User/Journal/Domain은 UUID다. | 모든 신규 ID/authorId/createdBy와 attachmentIds 원소를 UUID 문자열로 제안한다. 숫자 예시와 동일하다고 주장하지 않으며 구현 전 Common Contract의 타입·예시 정합성을 명시적으로 승인/반영한다. |
| Pagination | Common의 page/size 예시는 기존 Convention 우선을 허용한다. | Journal Document 목록은 `{items,total,nextCursor}`와 limit/cursor를 사용한다. page/totalPages를 동시에 제공하지 않는다. |
| Revision/재시도 | 기존 Journal은 revision과 필수 Idempotency-Key가 있지만 새 Document 계약에는 없다. | Document에 revision/If-Match/필수 Idempotency-Key를 임의 추가하지 않는다. PUT은 전체 상태 저장, 같은 Document의 요청 직렬화가 전제다. 서로 다른 Client의 stale save는 마지막 commit이 이기며, 생성/Upload timeout 뒤 자동 재시도는 중복을 만들 수 있음을 계약에 명시한다. 기존 Journal replay는 유지한다. |
| 게시 | Common §39는 Publish 이후에도 Save/첨부 제거를 수행한다. | PUBLISHED의 PUT을 허용하고 status/최초 publishedAt을 유지한다. Publish 재호출은 200의 동일 게시 상태, 추가 변경 없음. Unpublish API 및 Block 의미 기반 게시 검사는 없다. |
| 제약/상태 코드 | 파일 종류·크기, JSON 크기, title 상한, 삭제 응답의 세부 값은 확정되지 않았다. | 아래 제한값 및 HTTP 표를 초기 Local 정책으로 제안한다. 빈 title/content 배열을 생성·저장·게시할 수 있으며 Journal의 nonblank 본문 규칙을 전용하지 않는다. |
| 권한 경계 | FK 없는 범용 Space에서 현재 Journal 소유권을 확인해야 한다. | 아래 `DocumentSpaceAccess` 경계는 장기 의존 방향 결정이므로 **ADR 필요 후보**다. `PORTS_ADAPTERS.md`의 일반 내부 Service 포트 금지와 구분해 승인받는다. 미래 owner registry는 만들지 않는다. |
| 삭제 복구 | DB와 로컬 파일시스템을 원자적으로 commit할 수 없다. | 아래 첨부 전용 durable deletion intent/claim 및 부모 삭제 후 재시도는 **ADR 필요 후보**다. 일반 Outbox/Event Bus/분산 Transaction을 도입하지 않는다. 부모 삭제 200은 DB 삭제와 정리 작업 영속화를 의미하며 장애 시 Binary 제거는 지연될 수 있다는 계약 확인이 필요하다. |
| Local Storage | 사용자가 1차 구현체를 LocalFileStorage로 지정했다. 저장 Root/파일시스템 권한/영속 mount는 배치 환경마다 다르다. | 선택 자체를 다시 승인받지 않고 외부 Root 설정, 경로 안전성, 완성 파일 공개, Docker 재생성 후 파일 보존을 구현·검증 기준으로 고정한다. 구현 착수 승인은 별도다. |

새 ADR이 필요하다고 확정되면 Decision Guide에 따라 각각 proposed로 제시하고 명시적 승인 후 채택한다. 이번 작업에서는 ADR을 생성하지 않는다. Agent Guide 변경은 별도 승인 대상이다. LocalFileStorage는 기존 Java 파일 I/O 기능으로 계획하며 새 Storage 라이브러리나 별도 Storage 서버를 추가하지 않는다. 영속 경로·권한·안전한 파일 공개 조건이 성립하지 않으면 해당 의존 Session을 차단 사유와 함께 미완료로 둔다.

## Domain, Persistence and Journal Lifecycle

### 관계와 책임

```text
Journal.documentSpaceId ── 1:1 ──> DocumentSpace
Document.documentSpaceId ─ N:1 ──> DocumentSpace
Attachment.documentId ──── N:1 ──> Document
```

| Model/Table 제안 | 필드 및 제약 |
| --- | --- |
| DocumentSpace / document_spaces | id UUID PK, createdAt, updatedAt만 둔다. 상위 owner 필드와 역참조 Entity Collection 없음. |
| Journal / journals | document_space_id UUID NOT NULL UNIQUE FK 추가. Journal이 Space를 생성·독점 소유·삭제한다. 기존 필드/응답은 유지한다. |
| Document / documents | id UUID PK, document_space_id UUID NOT NULL FK, title NOT NULL, content JSONB NOT NULL, status DRAFT/PUBLISHED, author_id UUID NOT NULL, created_at/updated_at NOT NULL, published_at nullable. |
| Attachment / attachments | id UUID PK, document_id UUID NOT NULL FK, original_filename, object_key UNIQUE, content_type, size positive, status TEMP/ATTACHED, created_by UUID, created_at/updated_at. 필수 Metadata는 NOT NULL. |
| 첨부 삭제 작업 / attachment_object_deletions | Binary 정리에 필요한 objectKey, 대상 식별 정보, 작업 사유, 재시도/claim 정보를 보존하는 내부 운영 기록. 삭제된 부모에 대한 FK로 함께 사라지지 않는다. 사용자 Domain 또는 공개 상태를 추가하지 않는다. 승인 전 이름/세부 Schema를 확정된 구현으로 취급하지 않는다. |

Document/DocumentSpace에 journalId, projectId, milestoneId, ownerType, ownerId를 두지 않는다. 추가 workspaceId로 상위 관계를 복제하지도 않는다. Document의 상위 관계는 documentSpaceId뿐이며 authorId는 작성자 Audit이다. authorId/createdBy는 인증 User로 채우고 접근 권한으로 사용하지 않는다. User FK와 audit 불변 조건은 기존 User 영구 삭제 정책을 먼저 확인해 restrictive 참조로 정한다.

외래키는 부모 존재와 관계를 보장하되 Storage 정리를 DB cascade에만 맡기지 않는다. `documents(document_space_id,created_at DESC,id DESC)`, `attachments(document_id,status,id)`, TEMP의 `(updated_at,id)` partial index, 삭제 작업 due-time index를 후보로 하고 실제 query plan으로 검증한다. content의 최상위 JSON array와 상태/publishedAt 일관성은 DB CHECK로 보강한다. `attachmentIds` 배열 컬럼·별도 다대다 join table은 만들지 않는다.

`documentspace`, `document`, `attachment` feature에 현재 4개 Layer를 적용한다. Journal presentation/application은 Journal Context를 소유하고 generic Document Application 동작을 호출한다. Document/Space Domain과 Service에 Journal import나 Journal SQL을 넣지 않는다. 로컬 파일 I/O와 물리 경로 해석은 attachment infrastructure에만 둔다.

### 기존 데이터와 신규 Journal

**Migration backfill을 선택한다.** 현재 Flyway의 schema/data migration 선례, 엄격한 FK와 side-effect-free 조회에 맞는다. Lazy 생성은 nullable 관계, 최초 GET 쓰기, 경쟁/replay 예외를 늘리므로 채택하지 않는다.

- 실행 시 V18 다음 가용 Migration 번호를 확인하고 새 SQL만 추가한다. Space 생성 → Journal마다 별도 UUID Space backfill → non-null/unique/FK 검증 → NOT NULL 적용 순서다. 기존 Journal 수와 연결된 Space 수, 중복·고아·누락을 비교한다.
- 기존 Journal의 id/body/title/entryDate/revision/audit, Project 관계, 생성 replay 저장 bytes/hash/expiry는 변경하지 않는다. backfill Space의 audit는 해당 Journal audit에서 초기화한다. 기존 body로 Document를 생성하지 않아 초기 컬렉션은 비어 있다.
- 새 Journal 생성의 실제 신규 분기에서 Space를 만들고 Journal FK와 함께 같은 Transaction으로 저장한다. workspace dataRevision 및 replay 저장도 기존처럼 한 번이다. 어떤 단계 실패도 Space까지 rollback한다.
- Journal 생성 replay hit에는 Space를 만들지 않는다. 특히 삭제된 Journal의 유효한 과거 생성 replay가 기존 201 snapshot을 반환해도 Journal/Space/Document는 부활하지 않는다. 기존 Journal Response/Snapshot에 documentSpaceId를 추가할 필요는 없다.
- Space의 createdAt은 고정하고 Document 생성/삭제 등 컬렉션 변경에 updatedAt을 갱신한다. Document 저장은 Journal body/revision/audit를 변경하지 않는다.
- Local upgrade는 기존 writer를 종료한 coordinated restart를 전제로 한다. NOT NULL 도입 후 구버전 Journal writer와 병행 운용하지 않는다. 기존 V1–V18 수정, Flyway repair/baseline, Schema reset, ddl-auto update를 해결책으로 사용하지 않는다.
- 이 단계는 백업 복제/격리 DB에서 먼저 검증한다. 기존 Local DB Migration 및 Mini PC 배포 자체를 이번 PLAN 작성이나 과거 PLAN 승인으로 실행하지 않는다.

### 권한과 Transaction 경계 제안

`documentspace.application`의 owner-neutral `DocumentSpaceAccess` capability를 Journal infrastructure adapter가 구현하는 안을 제안한다. 이는 위 ADR 승인 대상이다. 입력은 인증 User와 Space ID/필요 동작이며 결과는 내부 소유 Workspace/잠금 context다. Document/Space에는 Journal 타입·ID가 반환되지 않는다. 상위 소유권용 Journal/Project query는 Journal adapter에만 위치한다. 한 provider만 구현한다.

- Journal 생성/목록 API는 먼저 Journal과 현재 Project의 소유권을 확인해 Space를 찾는다. Document detail은 필요한 id/spaceId projection을 찾은 뒤 이 경계로 권한을 확인한다. Attachment는 documentId → Space 권한을 사용한다. 검증 전 content/Binary를 읽거나 돌려주지 않는다.
- archived Project 아래 기존 Journal의 Document 작성·편집·게시·삭제를 허용하는 안을 제안한다. 이는 Journal 편집 정책의 연장이며 Project에 DocumentSpace를 연결하는 기능이 아니다.
- 변경은 **workspace → 정렬된 Project → Journal → Space → 정렬된 Document → 정렬된 Attachment → 삭제 작업** 순서를 유지한다. discovery 결과는 잠금 뒤 재검증한다. Journal 이동/삭제와 child 생성·저장을 같은 순서로 직렬화한다.
- background TEMP Cleanup은 Client 권한을 위조하지 않는 내부 maintenance 경로를 사용한다. live 부모가 있는 작업은 동일 ownership/잠금 context를 재확인하고, 이미 부모가 삭제된 Object 정리는 영속 작업만으로 진행한다. disabled User의 남은 TEMP도 회수할 수 있다.
- read-only REPEATABLE_READ 조회에 소유권, count/page 또는 Document+ATTACHED IDs를 포함한다. 저장은 Document 값과 Attachment reconciliation을 한 DB Transaction으로 commit한다. Storage I/O는 Transaction 밖이다.
- Document 생성/PUT/최초 Publish/삭제, Upload Metadata commit, Attachment 상태 변경·삭제는 논리 명령당 workspace dataRevision을 한 번 증가시킨다. 한 PUT의 여러 첨부 전이는 추가 증가하지 않는다. 동일 값 PUT은 기존 저장 Convention처럼 updatedAt/counter를 갱신한다. 읽기·실패·반복 Publish·Storage 재시도만으로는 증가하지 않는다.
- DB 변경의 관측 header는 같은 Transaction의 값을 사용한다. Content 응답은 권한/Metadata snapshot의 header로만 제공하며 Binary freshness를 증명한다고 설명하지 않는다. 공통 OpenAPI customizer가 부여하는 header와 실제 응답을 함께 검증한다.

## HTTP and JSON Contract

모든 API에 현재 Session/소유권/no-store를 적용하고, 변경 API에는 X-CSRF-Token/Origin 정책을 적용한다. 아래 URI·상태·UUID 정합성은 Session 1의 계약 확정 대상이다.

| Method / Path | 입력 | 성공 응답 |
| --- | --- | --- |
| POST `/api/v2/journals/{journalId}/documents` | JSON `{title,content}` | 201 DocumentResponse, DRAFT, attachmentIds=[], publishedAt=null |
| GET `/api/v2/journals/{journalId}/documents` | limit=20(1..100), optional cursor | 200 `{items:[DocumentSummary],total,nextCursor}` |
| GET `/api/v2/documents/{documentId}` | UUID | 200 DocumentResponse |
| PUT `/api/v2/documents/{documentId}` | JSON `{title,content,attachmentIds}` 전체 상태 | 200 DocumentResponse |
| DELETE `/api/v2/documents/{documentId}` | UUID, body 없음 | 200 `{deletedId}`; DB Hard Delete와 Binary 정리 영속화 |
| POST `/api/v2/documents/{documentId}/publish` | body 없음 | 200 DocumentResponse |
| POST `/api/v2/attachments` | multipart의 documentId 텍스트 field와 단일 file part | 201 AttachmentUploadResponse, TEMP |
| GET `/api/v2/attachments/{attachmentId}/content` | UUID | 200 Binary stream, 승인된 Content-Type/Disposition/Length |
| DELETE `/api/v2/attachments/{attachmentId}` | UUID, body 없음 | 200 `{deletedId}`; TEMP만 허용, Object 제거 뒤 Metadata 제거 |

전역 Document POST/GET Collection 및 DocumentSpace CRUD는 제공하지 않는다. Journal collection은 작성 맥락과 목록, Document detail은 전역 유일 ID 기반 편집이라는 역할을 OpenAPI Tag/operation description에서 구분한다. Document PUT을 Journal PATCH로 바꾸지 않는다.

- DocumentResponse: `id,documentSpaceId,title,content,attachmentIds,status,authorId,createdAt,updatedAt,publishedAt`만 반환한다. publishedAt은 **required nullable**이며 UTC ISO-8601이다. content는 JSON 문자열이 아니라 JSON array다. attachmentIds는 현재 ATTACHED rows에서 계산한 UUID 배열이며 안정적인 UUID 순서로 반환한다. Block 배치 순서는 content만 담당한다.
- DocumentSummary: `id,title,status,authorId,createdAt,updatedAt,publishedAt`. content/attachmentIds는 반환하지 않는다. DRAFT와 PUBLISHED를 모두 포함하고 `createdAt DESC,id DESC`로 정렬한다. 1차에 별도 검색·상태 필터는 추가하지 않는다.
- cursor는 resource namespace/인증 Workspace/Journal/Space/limit에 바인딩한다. 현재 HMAC 설정을 재사용하고 다른 Journal/사용자/limit/cursor 변조를 거부한다. count/page는 동일 snapshot이며 페이지 간 고정 snapshot은 보장하지 않는다.
- AttachmentUploadResponse: `id,documentId,originalFilename,contentType,size,status,contentUrl,createdAt`. contentUrl은 `/api/v2/attachments/{id}/content`의 안정적 상대 URL이다. objectKey, Storage Root/절대 파일 경로, 내부 저장 위치나 Storage 직접 접근 URL을 응답에 노출하지 않는다.
- 요청에서 authorId/createdBy/status/documentSpaceId/objectKey/id/audit 등 서버 필드는 거부한다. top-level unknown/duplicate keys, 명시적 null, 잘못된 UUID/타입, 반복·미지원 query/part를 기존 strict parsing 방식으로 거부한다.
- `content` 내부 임의 Block 필드/타입은 해석·정규화·삭제하지 않는다. 전체 JSON 문법/최상위 array/자원 한도만 검사하고 JSONB로 왕복한다. JSONB의 object key 순서·공백은 byte 단위로 보존하지 않으므로 의미상 동등성과 array 순서로 검증한다. Binary URL 치환이나 JSON 안의 Attachment ID 추론도 하지 않는다.
- Backend는 JSON 내부 URL의 용도를 판별하지 않는다. 따라서 Storage URL을 생성·주입하지 않고 서버가 제공하는 URL을 안정적 Application URL로 제한한다. Frontend가 이를 저장하는 계약은 유지하되 Block 의미 분석으로 강제하지 않는다.
- Publish는 직전 성공 PUT의 저장 상태를 게시한다. DRAFT→PUBLISHED 때 publishedAt/updatedAt을 지정하며 재호출은 그대로 반환한다. Client가 Save 성공 전에 Publish하거나 병렬 저장할 경우 최신 편집이 게시되었다고 보장하지 않는다. Backend에 autosave timer나 BlockNote renderer를 추가하지 않는다.

### 제안 Validation 정책

| 대상 | 1차 제안 |
| --- | --- |
| title | 필수 non-null String, 0..200 UTF-16 units, 빈 값 허용, 입력 문자열 보존. |
| content | 필수 JSON array, 빈 배열 허용. UTF-8 serialized content 최대 1 MiB, nesting depth 최대 100. 모든 Document JSON 요청의 parser/body 한도도 설정해 파싱 전에 무제한 메모리 사용을 막는다. |
| attachmentIds | PUT 필수 non-null 배열, UUID 원소 non-null/중복 불가, 최대 100. 생략은 오류, []는 전체 detach. 존재/동일 Document/권한을 모두 검증한 뒤 변경한다. |
| 파일 크기 | 0 byte 거부, 파일당 최대 10 MiB. multipart request 상한은 metadata overhead를 포함해 11 MiB로 제안하며 Servlet 및 Application 한도를 일치시킨다. |
| 허용 파일 | 초기 allowlist는 PNG(.png), JPEG(.jpg/.jpeg), PDF(.pdf), UTF-8 plain text(.txt). 선언 MIME·정규화 확장자·실제 signature/UTF-8 검사를 결합한다. 다른 형식은 승인된 정책 확대 전 거부한다. |
| Active Content | SVG/HTML/JS와 실행 파일·archive는 1차 거부. PDF/TXT는 attachment download, 검증된 PNG/JPEG만 inline. signature 검사를 완전한 악성 콘텐츠 검사라고 간주하지 않는다. |
| Filename | display metadata로만 사용. path component/제어문자/CRLF를 제거·거부하는 규칙과 최대 255 UTF-16 units를 고정하고 Content-Disposition에 안전하게 인코딩한다. Object Key에 원래 이름을 사용하지 않는다. |

Bean Validation으로 표현되는 required/size/UUID 배열 제약은 DTO와 Schema에서 일치시킨다. runtime 설정으로 달라지는 byte 제한·MIME/signature/존재·소유권/상태 검사는 operation 설명과 실제 오류로 명시한다. OpenAPI가 모든 business validation을 자동 표현한다고 주장하지 않는다. JSON byte 상한과 요청 전체 상한은 서로 다른 제한임을 문서화한다.

### Error Convention

| HTTP / code | 상황 |
| --- | --- |
| 400 VALIDATION_ERROR | JSON/UUID/필수 field/attachmentIds 중복·개수·잘못된 파일 등의 입력 오류; fieldErrors 포함 |
| 400 INVALID_CURSOR | 변조·다른 Context·잘못된 cursor |
| 401 AUTH_REQUIRED / 403 ACCOUNT_DISABLED 또는 CSRF_INVALID | 기존 Security 동작 유지; Origin 거부도 기존 공통 매핑 사용 |
| 404 RESOURCE_NOT_FOUND | 없거나 접근 불가인 Journal/Document/Attachment. 다른 Document의 attachmentIds도 같은 거부 형태로 처리해 타인 존재를 노출하지 않음 |
| 409 ATTACHMENT_IN_USE | ATTACHED 직접 삭제 |
| 409 ATTACHMENT_DELETION_PENDING | 삭제 claim이 확정된 TEMP를 연결하거나 직접 삭제를 중복 요청한 경우; proposed 추가 code |
| 413 PAYLOAD_TOO_LARGE | 파일·multipart·JSON 요청 크기 제한; Servlet/parser 단계도 공통 envelope로 매핑하는 proposed 추가 code |
| 415 UNSUPPORTED_MEDIA_TYPE | endpoint가 요구하는 JSON/multipart와 다른 HTTP request media type; 파일 allowlist 위반은 400 |
| 503 STORAGE_UNAVAILABLE | Upload/Content/TEMP 직접 삭제의 Storage 실패; 파일시스템 경로·내부 오류를 노출하지 않는 proposed 추가 code |
| 500 INTERNAL_ERROR | 예상하지 못한 DB/내부 오류는 기존 공통 처리 |

권한 검증은 상태 conflict보다 먼저 한다. 기존 workspace counter 한도 등의 오류는 기존 code를 유지한다. Content stream 전송 시작 후 장애는 JSON 오류로 바꿀 수 없으므로 연결 종료와 requestId 기반 내부 로그로 남긴다.

## Attachment and Storage Lifecycle

### Storage 경계와 Upload

Application 소유 `FileStorage`는 필요한 put/open/delete만 정의하고 **`LocalFileStorage`만 구현**한다. stream/size/contentType 및 논리 objectKey를 중립 계약으로 사용한다. Java Path/File, 물리 Root, 파일시스템 exception을 Domain/Application/Controller로 노출하지 않으며 adapter가 공통 Storage 오류로 변환한다. 별도 Storage 서버나 새 파일 I/O 라이브러리는 추가하지 않는다.

`objectKey`는 `documents/{documentId}/{server-generated-uuid}.{validated-ext}` 형태의 **Storage 독립적인 논리 Key**다. DB에는 이 Key만 저장하며 Root, OS drive, 절대경로, host 또는 volume 이름을 넣지 않는다. key 재사용·overwrite와 원래 filename 사용을 금지하고 UUID·확장자를 서버가 생성/검증한다. TEMP↔ATTACHED 전환으로 파일을 옮기거나 이름을 바꾸지 않는다. 향후 다른 FileStorage 구현체도 같은 Key를 해석하도록 경계를 유지하되 기존 Binary의 이동은 후속 PLAN에서 별도로 검증한다.

LocalFileStorage의 경로 및 파일 공개 규칙:

- `FILE_STORAGE_ROOT`를 필수 외부 설정으로 받고 명시적으로 준비한 전용 절대 경로를 사용한다. working directory, OS temp, classpath/static directory 또는 임의 fallback 경로를 기본값으로 사용하지 않는다.
- adapter만 Root와 논리 Key를 결합한다. 절대 Key, `..`, drive/UNC 경로, 잘못된 구분자 등 허용된 Key 형태 밖의 입력을 거부한다. 정규화된 경로와 실제 파일 경로의 Root 포함 여부를 확인하고 symlink/junction을 통한 외부 접근도 거부한다. 단순 문자열 prefix 비교로 경계 검증을 대신하지 않는다.
- Root와 하위 경로는 Application 실행 계정만 변경할 수 있게 준비하고 public static resource나 웹서버 alias로 공개하지 않는다. 경로 검사와 파일 열기 사이에 외부 프로세스가 link를 바꾸지 못하도록 파일시스템 권한과 adapter의 안전한 접근을 함께 적용한다.
- Root 내부 같은 파일시스템의 전용 staging 위치에 새 임시 파일을 만들고 크기 제한·bounded buffer로 기록한다. 기록 완료와 close/필요한 flush 후 최종 Key로 원자적으로 공개하며 기존 파일을 덮어쓰지 않는다. 대상 파일시스템이 필요한 atomic move를 지원하지 않으면 저장 실패로 처리하고 부분 파일을 최종 Key에 노출하는 fallback은 하지 않는다.
- 실패·중단 시 staging 파일을 회수하며 회수가 실패하거나 process가 종료되면 아래 orphan 복구 대상으로 남긴다. staging 파일은 Content API 대상이 아니다. process restart 및 Docker 재생성 시 보존을 검증하며 OS/디스크 전원 장애까지 DB와 파일의 동시 durability를 보장한다고 설명하지 않는다.

Upload는 Session/CSRF/Document 권한 및 입력 검증 → Transaction 밖 FileStorage put으로 완성 파일 공개 → 짧은 Transaction에서 부모/권한 재확인 및 TEMP Metadata insert 순서다. Upload 도중 부모가 삭제되면 Metadata commit을 거부하고 rollback이 확인된 파일을 보상 삭제한다. DB commit까지 성공한 후에만 201을 반환한다. 성공 Metadata 없이 ATTACHED를 만들지 않는다. commit 응답 유실은 rollback으로 단정하지 않고 아래 결과 불명확 분기로 처리한다.

### Save reconciliation

한 PUT Transaction에서 Document와 대상 Attachment를 일관된 순서로 잠그고 다음을 수행한다.

1. 요청 ID 전부에 대해 존재, 같은 Document, 현재 사용자 접근 가능 여부, 삭제 claim 부재를 검증한다. 하나라도 실패하면 title/content/status/audit/Attachment/counter를 전부 rollback한다.
2. 요청 집합의 TEMP를 ATTACHED로, 기존 ATTACHED 중 빠진 것을 TEMP로 전환한다. 요청에 계속 있는 ATTACHED와 이미 미사용 TEMP는 상태/audit를 불필요하게 갱신하지 않는다.
3. 상태가 변한 Attachment의 updatedAt을 같은 Clock 시각으로 갱신한다. detach 시점부터 Grace Period가 시작되며 오래된 createdAt 때문에 즉시 삭제되지 않는다. TEMP가 다시 사용되면 ATTACHED로 복귀한다.
4. Document title/content/updatedAt과 상태 전이를 함께 commit하고, 응답의 attachmentIds를 ATTACHED 관계에서 산출한다. Binary 존재를 매 저장마다 Storage I/O로 검사하거나 JSON Block에서 관계를 추정하지 않는다.

### Content delivery

**Backend Streaming을 유지한다.** Frontend는 인증된 Application Content URL만 호출하고 LocalFileStorage의 경로나 파일에 직접 접근하지 않는다. Storage 직접 URL로 Redirect하지 않는다.

Metadata/권한을 짧은 read Transaction에서 확인한 뒤 FileStorage로 파일 stream을 열어 전달한다. TEMP도 소유자가 미리보기할 수 있지만 삭제 claim 이후에는 content 조회를 404로 거부한다. Root 비공개, no-store, `X-Content-Type-Options: nosniff`, 검증된 Content-Type, 안전한 Content-Disposition을 적용한다. 파일 전체를 byte[]로 올리지 않고 timeout·동시 stream 수·buffer를 제한하며 정상 종료/Client abort/실패 모두 stream을 닫는다. Content-Length와 빈 파일/유실 Object 처리도 검증한다.

1차에는 Range/conditional cache/preview conversion을 구현하지 않는다. 조회 도중 삭제와 경합하면 이미 열린 stream이 끝까지 전송될 수 있으며, 권한 확인 이후 즉시 전송 중단까지 보장하는 잠금을 유지하지 않는다. 정상 Storage Root 안에서 없는 파일은 내부 손실로 기록하고 일반적인 not-found 오류로 반환한다. Root 자체의 접근 불가·mount 이상·읽기 권한 오류를 파일 부재로 오인하지 않고 Storage 실패로 처리한다. 열린 파일 삭제가 거부되는 환경에서는 실패 작업을 남겨 stream 종료 후 재시도한다.

### TEMP 삭제·Cleanup과 동시성

- 기본 Grace Period **24시간**, Scheduler **fixed delay 10분**, 한 번에 **100개**, `(updatedAt,id)` 순서로 제안한다. injected Clock으로 `status=TEMP AND updatedAt < cutoff`를 판정한다. cutoff와 같은 timestamp는 다음 대상이다.
- 기존 Spring Scheduling을 사용하되 현재는 Scheduler가 없으므로 enable/config/단일 Job을 신규 추가한다. Quartz/ShedLock/Redis/메시지 브로커는 도입하지 않는다. 테스트에서는 자동 실행을 끄고 명시적으로 tick한다.
- 후보 SELECT만으로 바로 Binary를 지우지 않는다. 짧은 Transaction에서 부모/Document/Attachment를 같은 순서로 잠그고 상태·updatedAt을 재확인한 뒤 **영속 삭제 claim**을 기록한다. Save가 먼저 이기면 Cleanup은 skip하고, claim이 먼저 이기면 Save는 409로 전체 rollback한다.
- claim 이후 FileStorage로 파일을 Transaction 밖에서 삭제하고, 삭제 성공 또는 정상 Root에서 대상 파일 부재가 확인될 때 새 Transaction으로 Attachment Record와 작업을 제거한다. 공개 status는 TEMP/ATTACHED 두 개를 유지하고 삭제 중 상태는 내부 작업으로만 표현한다.
- TEMP 직접 삭제도 같은 경로를 사용하되 Grace Period를 기다리지 않는다. 완료 후 200, Storage 실패는 503과 영속 retry를 남긴다. ATTACHED 직접 삭제는 409이며 Object/Metadata에 영향이 없어야 한다.
- 실패한 삭제 재시도로 Attachment.updatedAt을 갱신해 Grace Period를 연장하지 않는다. 삭제 작업은 due time/attempt/lease로 중복 worker를 제어하고 crash 후 lease 만료로 재개한다. 중복 파일 delete 및 이미 없는 row는 idempotent하게 처리한다. 파일 부재만 삭제 완료로 취급하며 권한 거부·read-only 파일시스템·열린 파일 잠금·Root/mount 접근 오류는 완료 처리하지 않는다.

### Document/Journal Hard Delete와 Partial Failure

Document 삭제는 관련 모든 TEMP/ATTACHED objectKey의 삭제 작업을 DB Transaction 안에 먼저 확보하고 Attachment Metadata → Document를 제거한다. Space는 Journal이 살아 있는 동안 남긴다. Journal 삭제는 기존 ownership/revision 검사 후 동일하게 모든 child의 작업을 확보하고 Attachment → Document → Journal → DocumentSpace 순으로 FK에 맞게 제거한다. 각 부모 명령의 counter는 한 번이며 기존 Journal 200 deletedId/반복 404는 유지한다. 이미 Cleanup/직접 삭제 claim이 있으면 objectKey 기준으로 기존 작업을 재사용하며, 작업 기록의 부모/Attachment 참조는 cascade로 작업을 없애거나 RESTRICT로 부모 삭제를 막는 FK로 만들지 않는다.

부모 삭제 작업의 영속화에 실패하면 전체 DB 삭제를 rollback하고 Object를 먼저 삭제하지 않는다. commit 뒤 즉시 bounded 정리를 시도하고 실패분은 Scheduler가 재시도한다. 정상 경로의 모든 Binary 제거와 장애 후 최종 제거를 모두 검증한다. 부모 삭제 200을 모든 첨부파일의 즉시 제거 완료로 설명하지 않는다.

| 실패 지점 | 처리와 복구 기준 |
| --- | --- |
| 파일 기록/최종 공개 실패·중단 | Metadata 성공을 기록하지 않는다. 해당 작업의 종료를 확인하고 staging 및 최종 Key의 결과를 대조해 보상한다. 디스크 부족·권한 거부·I/O 오류를 구분하며 진행 중 쓰기를 실패 응답만으로 종료되었다고 단정하지 않는다. Key 재사용은 없다. |
| put 성공 → DB insert/commit의 rollback 확정 | Object 보상 삭제. 보상 실패 시 가능한 별도 Transaction으로 삭제 작업을 남긴다. 실패 Transaction 안에 retry 기록을 저장하지 않는다. |
| DB commit 응답/connection 유실로 결과 불명확 | 즉시 Object를 삭제하지 않는다. 원래 Transaction 종료를 확인하고 서버 생성 Attachment ID/objectKey로 새 Transaction에서 committed Metadata를 대조한다. live Metadata가 있으면 Binary를 보존한다. 원래 Transaction 종료·미참조 상태를 확정할 수 없거나 DB가 불가하면 삭제를 보류하고 안전한 복구 기록을 남긴다. 한 번의 조회에서 row가 없다는 사실만으로 늦은 commit을 배제하지 않는다. |
| Object delete 성공 → Metadata/작업 삭제 실패 | 영속 작업을 보존하고 재실행한다. Root 접근이 정상인 상태의 파일 부재를 성공으로 취급해 DB 정리를 마무리한다. |
| Object delete 실패 | TEMP claim/작업을 보존하고 backoff로 재시도한다. ATTACHED 전환을 허용하지 않으며 부모가 이미 삭제돼도 objectKey가 유실되지 않는다. |
| 부모 DB delete rollback | children/작업/counter도 rollback, after-commit Binary 제거가 실행되지 않는다. |
| 프로세스 종료 | commit된 삭제 작업은 재기동 후 재개한다. 메모리 callback/로그만을 삭제의 유일한 근거로 사용하지 않는다. |
| 파일 기록/최종 공개 직후 process crash, 또는 DB 불가 + 보상 실패 | DB에 key를 남기지 못한 최종 파일과 미완성 staging 파일의 orphan 가능성이 있다. 1차의 보상 처리만으로 crash-safe upload를 보장하지 않는다. 아래 운영 복구 절차로 회수한다. |

과도한 분산 Transaction/일반 이벤트 플랫폼 대신 첨부 삭제만을 위한 작업 기록을 제안한다. Upload 전체를 새 분산 상태 머신으로 만들지는 않는다. 남은 orphan 회수는 **새 Upload writer 중지·진행 중 요청과 파일 쓰기/DB Transaction 종료 확인 → Root 내부의 충분히 오래된 최종 파일과 staging 파일을 Metadata/삭제 작업과 대조 → 검토된 미참조 파일만 삭제**하는 Local 복구 절차로 정의한다. Client timeout만으로 파일 작업 종료를 단정하지 않는다. 정규 TEMP 만료 기준은 계속 DB의 status/updatedAt이며 파일 mtime으로 대체하지 않는다. 복구 시에도 Root 경계를 벗어나거나 link를 따라가며 파일을 열거·삭제하지 않는다. DB 조회 불가 시 또는 진행 중 Upload 여부가 불명확하면 삭제하지 않는다. Storage 실패/보상 실패 테스트에는 이 절차의 dry-run 결과도 포함한다.

로그에는 requestId/작업 ID/Document·Attachment 식별자, 재시도 횟수·오류 분류를 남긴다. orphan 복구에 필요한 objectKey는 접근 제한된 내부 기록으로 남길 수 있으나 API/Document JSON/일반 사용자 메시지에는 노출하지 않는다. 물리 경로·민감한 시스템 정보·파일 본문을 일반 오류 메시지에 기록하지 않는다. backoff는 상한을 두고 반복 실패를 관측 가능하게 하며 작업을 자동 포기·삭제하지 않는다.

## Local Environment and Change Boundaries

- Spring Boot Local 실행과 기존 PostgreSQL Compose 서비스·volume을 유지한다. Binary는 host에서 별도로 준비한 영속 디렉터리에 저장한다. 기존 DB/volume을 초기화하거나 첨부파일을 저장소 소스·빌드 출력·OS 임시 디렉터리에 두지 않는다.
- 기존 `DB_URL/DB_USERNAME/DB_PASSWORD/DB_NAME/DB_PORT`, `.env` import, 외부 `SPRING_PROFILES_ACTIVE`를 유지한다. `FILE_STORAGE_ROOT`는 `app.storage.root` 같은 typed external properties에 매핑한다. 파일/content size, TEMP grace, cleanup interval/batch/enabled, streaming 제한도 환경변수 또는 기존 외부 설정으로 주입한다.
- 제안 신규 변수군은 `FILE_STORAGE_ROOT`, `ATTACHMENT_*`, `DOCUMENT_CONTENT_MAX_BYTES`다. 구현 승인 후 루트 `.env`에 환경별 값을, `.env.example`에 안전한 예시를 함께 추가한다. 실제 `.env`와 DB credential은 커밋하지 않는다.
- Docker로 Application을 실행할 때는 **persistent named volume 또는 host bind mount를 FILE_STORAGE_ROOT가 가리키는 컨테이너 경로에 연결**한다. 컨테이너 writable layer, tmpfs, 이미지 내부 디렉터리에 영속 Binary를 의존시키지 않는다. host 경로/mount 선택과 컨테이너 내부 Root를 외부 배치 설정으로 정하고 Local용 절대경로를 이미지에 고정하지 않는다.
- 배포 전에 전용 Root와 초기화 표식, 실행 계정의 읽기·쓰기·삭제 권한을 준비한다. Application은 시작 시 Root/표식을 검증하고 잘못된 mount·누락된 Root를 빈 새 저장소로 자동 대체하지 않는다. Docker의 non-root 실행 계정과 volume/bind mount 권한을 확인한다. 실행 중 Root/표식 접근이 실패하면 Cleanup도 중단·재시도하고 파일 부재를 이유로 Metadata를 지우지 않는다.
- Storage I/O의 디스크 부족/권한 변경/read-only/mount 장애는 Upload/Content/cleanup에 격리한다. 기동 후 DB만 사용하는 Document 조회/저장은 유지한다. stream/동시 I/O 자원을 제한하고 느린 요청·Client abort 후에도 파일 핸들과 작업을 회수한다.
- Local과 Mini PC는 **같은 bootJar/Application Artifact**를 사용하고 Root·DB·Profile·mount만 외부에서 다르게 지정한다. Root/OS 경로를 Java 상수·빌드 분기·DB objectKey·Document JSON에 저장하지 않는다. 서로 다른 독립 Root를 쓰는 다중 Application 인스턴스 운용은 1차 범위 밖이다.
- Root 설정 변경은 기존 파일을 자동 이동시키지 않는다. 환경 이동/복구 시 writer와 cleanup을 정지하고 DB Metadata/삭제 작업 및 파일 Root를 함께 보존·복원한 뒤 논리 Key 대조, Content 조회, pending cleanup을 검증하는 절차를 기록한다. volume 삭제를 수반하는 정리 명령을 재기동 절차로 안내하지 않는다. Mini PC 운영 배포 자체는 범위 밖이다.
- 예상 변경 책임: 신규 feature packages, Journal lifecycle/내부 FK, 신규 Flyway SQL, 공통 예외/OpenAPI의 필요한 부분, LocalFileStorage와 external properties, Docker mount 사용 문서/필요한 배치 설정, feature 및 파일시스템 통합 테스트. 새 Storage 서비스·클라이언트 의존성이나 관련 없는 기존 Service 리팩터링은 포함하지 않는다.

## Execution Sessions

Execute sessions in order. Do not start the next session until the current session's implementation and validation are complete. Check an item only after the corresponding work has actually been completed. Update this Plan as execution progresses.

사용자가 제시한 흐름을 9개 Session으로 조정한다. 상세 조회가 Attachment 관계에 의존하므로 신규 세 Domain의 Persistence를 먼저 준비한다. Journal FK/backfill/NOT NULL은 신규 생성·삭제 연결과 같은 Session에서 적용해 중간 단계의 기존 Journal writer를 깨뜨리지 않는다. Document 생성/조회로 부모를 확보한 뒤 Upload를 구현하고, Attachment가 준비된 후 PUT reconciliation/Publish를 완성한다. 삭제는 Storage 복구 기반이 준비된 뒤 묶는다. Security/Validation은 각 API Session부터 적용하며 마지막 Session으로 미루지 않는다. 중간 Session 완료는 전체 기능의 배포 가능 판정이 아니다.

### Session 1: 계약, ADR 및 Migration 전제 확정

Objective:

구현 전에 문서 차이와 장기 설계 결정, LocalFileStorage의 Root·파일시스템 조건 및 Migration 출발점을 확정한다.

Implementation:

- [ ] 현재 Journal/code/Migration 상태를 재확인하고 URI·UUID·DTO·상태 코드·제한값·게시/삭제 의미를 요구사항/Common Contract와 대조한 승인 계약을 기록한다.
- [ ] owner-neutral 권한 경계와 durable deletion claim/retry의 ADR 필요 여부를 확정하고, 필요 ADR을 proposed로 검토·승인받는다. Guide 변경을 자동 포함하지 않는다.
- [ ] FileStorage 경계를 유지한 LocalFileStorage, 논리 Key와 물리 경로 분리, Root 초기화·경로/권한·원자적 파일 공개·Docker 영속 mount 및 오류 복구 기준을 고정한다. 새 Storage dependency는 추가하지 않는다.
- [ ] V18 이후 가용 Migration 번호, 기존 Journal/backfill 규모, 격리 upgrade fixture 및 기존 replay 보존 검증 기준을 정한다.

Validation:

- [ ] 숫자 ID 예시/UUID, pagination, 삭제 200 의미 등 차이가 미해결 상태로 구현에 넘어가지 않는지 확인한다.
- [ ] Diagram/import 책임에 Document/Space→Journal 직접 의존 또는 ownerType/ownerId가 없고 Journal 외 연결이 없는지 검토한다.
- [ ] 실제 격리 파일 Root와 기존 PostgreSQL Testcontainers, Local host 및 Linux Application 컨테이너의 persistent volume/bind mount로 검증할 수 있는지 확인한다. Root 외부 접근 방지·권한·원자적 파일 공개 조건이 미해결이면 관련 후속 Session을 진행하지 않는다.

### Session 2: DocumentSpace/Document/Attachment Persistence

Objective:

신규 범용 Domain의 관계와 opaque JSONB 왕복을 DB 수준에서 성립시키고 이후 상세 조회의 관계 의존성을 준비한다.

Implementation:

- [ ] Space/Document/Attachment Domain·Repository·Persistence와 신규 Table Migration을 추가한다. Journal의 FK/backfill/NOT NULL은 Session 3에서 lifecycle 변경과 함께 적용한다.
- [ ] Document→Space/Attachment→Document NOT NULL FK, 상태/JSON array 제약 및 query index를 추가한다. Attachment 테이블은 상세 응답의 ATTACHED IDs 조회에 사용하며 Upload는 Session 5에서 구현한다.
- [ ] Clock/audit/UUID/JSONB mapping, Document DRAFT와 Attachment TEMP factory를 기존 패턴으로 구현한다. 기존 body를 Document로 변환하지 않는다.

Validation:

- [ ] 빈 DB 전체 Migration 및 populated V18 upgrade를 격리 PostgreSQL에서 수행하고 Flyway checksum/JPA validate를 확인한다.
- [ ] 신규 FK/non-null/상태 제약 및 기존 Journal id/body/date/audit/revision/replay 불변을 검증한다. 현재 Journal 생성 경로는 계속 동작해야 한다.
- [ ] flush/clear 후 빈 배열, 중첩/알 수 없는 Block, Unicode/숫자/배열 순서의 JSONB 의미상 왕복을 검증한다.
- [ ] 잘못된 Document/Attachment FK·상태·JSON shape를 DB가 거부하고 신규 Migration 실패가 부분 Schema 변경을 남기지 않는지 확인한다.

### Session 3: Journal Backfill, 생성·삭제 Lifecycle 및 공용 접근 경계

Objective:

기존 및 신규 Journal에 Space를 제공하면서 기존 writer의 유효성을 유지하고 공용 Document/Attachment 권한 확인의 기반을 마련한다.

Implementation:

- [ ] 신규 Migration으로 Journal 내부 scalar FK/backfill/NOT NULL/UNIQUE를 추가하는 것과 같은 Session에서 실제 생성 분기에 Space 생성을 연결한다. rollback/replay/counter 규칙과 기존 Journal fixture를 함께 맞춘다.
- [ ] 승인된 DocumentSpaceAccess와 Journal adapter를 구현하고 조회·변경·maintenance context의 잠금 순서를 고정한다.
- [ ] 기존 Journal DTO/검색/body/revision/archived 정책을 유지한다. 빈 Space의 Journal 삭제는 기존 Hard Delete와 함께 처리하고 첨부가 있는 삭제 완성은 Session 7에서 검증한다.

Validation:

- [ ] 기존 Journal이 있는 직전 Schema 및 V18에서 최신까지 upgrade하고 Journal 수=연결 Space 수, non-null/unique/FK, 누락·고아 없음과 기존 audit/replay bytes 불변을 확인한다. 실패 시 부분 backfill이 남지 않아야 한다.
- [ ] Journal+Space+replay atomic commit 및 flush/commit 실패 시 전체 rollback을 검증한다.
- [ ] 동일 key 재처리, archive 후 replay, 삭제 후 replay에서 추가 Space/Journal이 생기지 않는지 확인한다.
- [ ] 소유자/타인/없는 리소스, archived Project, disabled User 및 Journal 이동 경쟁의 접근 규칙을 검증한다.
- [ ] 기존 Journal Persistence/Command/Query/API/OpenAPI 회귀를 실행하고 Document 패키지에 Journal import/SQL이 없는지 확인한다.

### Session 4: Journal Document 생성/목록 및 공용 상세 조회

Objective:

Journal 맥락에서 DRAFT를 만들고 전역 documentId로 같은 내용을 조회한다.

Implementation:

- [ ] Journal nested POST/GET와 Document detail GET, 정확한 DTO/strict parser/Bean Validation/중앙 오류를 추가한다.
- [ ] signed cursor, summary projection, 동일 snapshot의 count/page/권한 및 audit/counter 응답을 적용한다.
- [ ] Session/CSRF/Origin을 적용하고 경로·예시·schema를 OpenAPI에 등록한다. PUT/Publish/삭제를 임시 성공 stub으로 노출하지 않는다.

Validation:

- [ ] Journal마다 별도 Space에 DRAFT 생성, 빈 title/content 허용, 전역 상세 동일 값과 서버 필드 위조 거부를 검증한다.
- [ ] 두 사용자와 두 Journal의 격리, empty collection, 100개 초과 fixture의 여러 page/total/동일 timestamp 정렬 및 cursor Context 거부를 검증한다.
- [ ] JSON 크기/깊이/타입/unknown top-level 및 malformed 입력, 인증/CSRF/Origin 오류를 실제 HTTP/MockMvc에서 확인한다.
- [ ] 신규 3개 operation이 `/v3/api-docs`에 있고 global Document collection/Space CRUD가 없는지 확인한다.

### Session 5: LocalFileStorage Upload와 보상 처리

Objective:

존재하는 Document에 검증된 Binary와 TEMP Metadata를 생성하고 실패 시 보상한다.

Implementation:

- [ ] FileStorage/LocalFileStorage와 삭제 작업 기록의 신규 Migration을 구현하고 Session 2의 Attachment Persistence에 연결한다. Root 외부 설정, 경로 안전성, staging/원자적 공개 및 Docker volume/bind mount 사용 문서를 추가한다.
- [ ] multipart documentId/file 입력, 파일 validation, LocalFileStorage put, 부모 재검증, TEMP insert/commit 및 rollback 보상을 구현한다.
- [ ] Upload DTO와 stable contentUrl/OpenAPI를 제공하고 파일 I/O 오류를 중앙 오류 계약으로 변환한다.

Validation:

- [ ] JUnit 임시 디렉터리 등 격리된 실제 파일 Root와 PostgreSQL Testcontainers로 Upload하고 파일 bytes/size, Metadata contentType/논리 Key, TEMP 상태를 비교한다. in-memory Storage mock만으로 통합 성공을 판정하지 않는다.
- [ ] 빈 파일, 10 MiB 경계/초과, multipart 상한, MIME/extension/signature 불일치, SVG/HTML, filename path/CRLF, 반복 part 및 다른 사용자 Document를 거부한다.
- [ ] 디스크 부족·권한 거부·read-only·기록 중단·atomic move 실패, 파일 공개 후 DB flush·commit rollback, 보상 실패, Upload 중 부모 삭제를 검증한다. 재현하기 어려운 파일 오류는 adapter의 제한된 fault injection으로 보완한다. commit 성공 후 응답/connection 유실과 결과 불명확 상태에서도 살아 있는 파일을 삭제하지 않는지 확인한다.
- [ ] 절대경로/상위 경로/drive·UNC/구분자 변형/symlink·junction escape를 거부하고 Root 밖 파일이 읽히거나 바뀌지 않는지 검증한다. overwrite·부분 파일 노출·staging 조회도 거부한다.
- [ ] 공개 응답과 DB objectKey에 Root/물리 경로가 없고 외부 설정만 바꿔 같은 Artifact로 서로 다른 격리 Root를 사용하는지 검증한다. Root 누락/잘못된 mount에서 fallback하지 않아야 한다.

### Session 6: Document PUT, Attachment Reconciliation 및 Publish

Objective:

content와 attachmentIds 전체 상태를 원자적으로 저장하고 양방향 첨부 상태 전이 및 게시를 완성한다.

Implementation:

- [ ] PUT의 관계 Source of Truth, 전체 검증 후 TEMP↔ATTACHED 전이와 Document/audit/counter 단일 Transaction을 구현한다.
- [ ] DocumentResponse의 ATTACHED IDs를 DB에서 계산하고 JSONB 내부 Block은 그대로 유지한다.
- [ ] DRAFT/PUBLISHED 규칙과 Publish/반복 Publish, 게시 후 PUT을 구현한다. revision/history/Unpublish는 추가하지 않는다.

Validation:

- [ ] TEMP→ATTACHED→TEMP→ATTACHED, [] 전체 detach, 누락/중복/없는 ID/타 Document·타인 ID와 삭제 claim 충돌을 검증한다.
- [ ] 잘못된 한 ID 및 중간 실패에서 content/title/audit/관계/counter 모두 rollback되는지 별도 Transaction으로 확인한다.
- [ ] 변경 없는 ATTACHED/TEMP의 updatedAt 보존, detach 시 Grace 재시작 및 기존 오래된 createdAt 영향을 검증한다.
- [ ] Publish 전/후 저장, 반복 Publish, publishedAt 최초값 보존, 요청 직렬화/경쟁 저장 시 DB·응답 관계 일관성을 검증한다.
- [ ] JSON Block에 ID가 있어도 attachmentIds에 없으면 TEMP, JSON에 없어도 요청 ID가 유효하면 ATTACHED가 되어 Backend가 Block을 해석하지 않음을 확인한다.

### Session 7: Content Streaming, 삭제 및 TEMP Cleanup

Objective:

안정적인 Content URL과 부모 Hard Delete를 제공하고 경쟁·Storage 실패·재시작 후에도 정리를 복구한다.

Implementation:

- [ ] 권한 확인 후 Streaming, 안전한 response header/stream 종료/timeout을 구현한다.
- [ ] TEMP 직접 삭제, ATTACHED 삭제 거부, 영속 claim 기반 bounded Scheduler/retry를 구현한다.
- [ ] Document/Journal 삭제에 모든 objectKey 작업 확보와 DB Hard Delete를 연결하고 after-commit 정리·재시작 복구를 구현한다.
- [ ] orphan dry-run/검토/회수 절차와 실패 로그·재시도 관측 방법을 문서화한다.

Validation:

- [ ] 소유 TEMP/ATTACHED content, 타인/없음/claim 후 content, 정확한 bytes/headers, 느린 응답/Client abort/stream 종료를 검증한다.
- [ ] ATTACHED 직접 삭제 409, TEMP 즉시 삭제, cutoff 이전/동일/이후, batch 경계, Grace 중 재연결 및 두 worker의 중복 처리를 검증한다.
- [ ] latch와 별도 Transaction으로 Save↔Cleanup, Save↔직접 삭제, Upload/생성↔Document/Journal 삭제의 양쪽 승리 순서를 반복 검증한다. 살아 있는 ATTACHED Object가 지워지면 실패다.
- [ ] Document 삭제는 Space를 보존하고 Journal 삭제는 Space/모든 Document/Attachment를 제거하는지, 기존 Journal revision/replay/반복 404를 보존하는지 확인한다.
- [ ] Object delete 실패, delete 성공 후 DB 실패, 부모 Transaction rollback, commit 직후 프로세스 종료/재기동에서 작업이 유실되지 않고 최종 정리되는지 실제 파일 Root와 PostgreSQL로 검증한다. 파일 부재는 성공, 권한/mount 오류는 재시도로 구분하고 Windows/Linux의 열린 stream 삭제 차이와 stream 종료 후 재시도를 확인한다.
- [ ] 이미 Cleanup/직접 삭제 claim이 있는 Attachment의 부모 Hard Delete가 작업을 재사용하고, 진행 중 worker와 겹쳐도 부모 삭제를 막거나 key를 잃지 않는지 검증한다.
- [ ] 보상 기록도 불가능한 최종 파일/staging orphan fixture를 만들고 writer 중지 확인 및 dry-run 대조가 살아 있는 파일이나 Root 밖 경로를 회수하지 않는지 검증한다.

### Session 8: Security, Validation, Error 및 OpenAPI 통합

Objective:

9개 API의 런타임 행동과 승인된 Common Contract/Swagger를 일치시킨다.

Implementation:

- [ ] 각 Session의 Security/Validation 결과를 종합하고 framework multipart/parser 예외까지 공통 Error에 맞춘다.
- [ ] Request/Response/nullable fields/enums/UUID/opaque JSON/multipart/binary schemas, query/header/상태 코드/description을 정리한다.
- [ ] 기존 공통 OpenAPI customizer·required field·response header 검사와 새로운 Content endpoint의 호환성을 맞춘다.

Validation:

- [ ] 9개 operation 각각에 적용되는 정상/401/403/404 및 해당 400/409/413/415/503 오류를 확인하고 타인 존재·물리 경로/Storage 정보를 노출하지 않는지 검증한다. 조회 API에 불필요한 mutation conflict를 요구하지 않는다.
- [ ] Create/PUT/Upload의 writable field whitelist, 서버 필드 위조, Bean Validation Schema 반영, PUBLISHED 저장과 stable URL 계약을 검증한다.
- [ ] `/v3/api-docs`의 method/path/status/schema를 승인된 Common Contract와 기계적으로 대조한다. 숫자 ID 예시가 UUID로 미해결인 채 통과시키지 않는다.
- [ ] 기존 `src/test/browser` 방식을 확장해 Swagger UI의 Journal collection/Document detail/Attachment multipart·binary 렌더링과 try-it-out을 실제 Local HTTP에서 확인한다. Frontend 제품 코드는 구현하지 않는다.
- [ ] development/test 익명 문서와 default/production 보호 정책, 기존 Journal 및 다른 API의 회귀를 확인한다.

### Session 9: Migration, 장애 복구 및 End-to-End 최종 검증

Objective:

Local에서 전체 사용자 흐름과 기존 기능 보존을 재현하고 검증 근거를 남긴다.

Implementation:

- [ ] 격리 DB/파일 Root 및 Local Spring Boot용 실행 절차, 외부 설정 예시, Docker 영속 mount와 실행 계정 권한, API 계약과 정리/복원 절차를 마무리한다.
- [ ] fresh/populated upgrade, 실제 Storage 연동, concurrency/failure, OpenAPI/browser 결과를 후속 검증 기록에 정리한다.
- [ ] 전체 테스트/빌드 결과와 생성 Artifact의 새 Migration·config 포함을 확인하고 불필요한 test 전용 설정/자격증명이 포함되지 않게 한다.

Validation:

- [ ] Journal 조회→목록→DRAFT 생성→Upload TEMP→PUT ATTACHED→재조회 JSON/파일 복원→Publish→목록→상세→첨부 제거 PUT TEMP→Grace/Cleanup→Document 삭제→Journal 삭제를 실제 HTTP/DB/LocalFileStorage로 검증한다.
- [ ] 생성된 파일/Metadata/Space 수를 각 단계에서 대조하고 정상 종료 및 fault/restart 후 고아·지워진 ATTACHED 파일·소유권 누수가 없는지 확인한다.
- [ ] Application 컨테이너를 제거·재생성해도 같은 persistent volume 또는 bind mount와 DB로 첨부파일을 다시 조회하고 pending cleanup을 재개할 수 있는지 검증한다. 배치 문서에 제공하는 두 mount 방식과 non-root 권한을 확인하며 volume 데이터는 삭제하지 않는다.
- [ ] 같은 Artifact의 Root 외부 설정 변경, Root/DB의 격리 복제·복원 후 동일 objectKey/contentUrl 동작, Root/표식 누락 시 오류를 검증한다. 설정 변경만으로 파일이 이동한다고 가정하지 않는다.
- [ ] 대표 데이터의 list/content/delete/cleanup에 N+1·전체 Binary 메모리 적재·무제한 batch·Storage 대기 중 DB lock이 없는지 query plan/실행 관측으로 검증한다.
- [ ] 기존 테스트 구조로 `./gradlew.bat test`와 `./gradlew.bat check bootJar`를 실행한다. Session별로 영향 테스트를 먼저 수행하고 이후 코드 변경/실패 근거 없이 전체 suite를 반복하지 않는다.
- [ ] Docker/PostgreSQL 통합 환경 또는 실제 파일 Root/mount 검증 불가로 필수 테스트가 skip되면 완료로 처리하지 않는다. Local 검증 결과를 Mini PC 또는 외부 환경 배포 성공으로 확대하지 않는다.

## Final Validation

- [ ] DocumentSpace의 최소 모델/1:1/NOT NULL/기존 데이터 backfill, 신규 Journal 원자 생성과 역사적 replay를 검증했다.
- [ ] Journal만 상위 연동되며 Document/Space에 Domain 전용 FK·polymorphic owner·직접 Journal 의존이 없다.
- [ ] Document 생성/목록/상세/PUT/Publish/삭제, JSONB opaque round-trip과 권한·입력 제한을 검증했다.
- [ ] attachmentIds는 API 관계 정보이며 DB 컬럼이나 Block 분석 결과가 아니다. TEMP↔ATTACHED와 잘못된 관계의 전체 rollback을 검증했다.
- [ ] 실제 LocalFileStorage Upload/Content/Delete, Root 비공개/stable URL, ATTACHED 직접 삭제 거부, TEMP Cleanup 및 부모 삭제 복구를 검증했다.
- [ ] Storage/DB partial failure와 process restart, Upload orphan의 명시적 복구 한계를 검증하고 기록했다.
- [ ] 기존 Journal body/검색/revision/replay/Hard Delete/archived 동작과 기존 API/Migration 회귀가 통과했다.
- [ ] Root 경계·link escape·부분 파일/overwrite 방지, 같은 Artifact의 Root 외부 설정 변경, Docker persistent volume/bind mount 재생성 후 보존·복원, 최종 test/check/bootJar와 격리 E2E가 통과했다.
- [ ] No failures caused by this Plan remain.
- [ ] All Execution Sessions are complete.

### API Validation

- [ ] Implemented endpoints are present in `/v3/api-docs`.
- [ ] Request and response schemas match the approved `DOCUMENT_EDITOR_COMMON_CONTRACT.md`, including resolved UUID examples, pagination, opaque content, attachmentIds, required nullable publishedAt and upload DTO.
- [ ] Bean Validation constraints are reflected where applicable; dynamic byte limits and business constraints are documented and tested separately.
- [ ] HTTP methods and response status codes match the contract, including multipart/content responses and common error envelopes.
- [ ] Swagger UI renders the implemented endpoints correctly using the repository's browser validation approach.
- [ ] Journal Collection API와 documentId 기반 Detail API의 역할이 구분되고, 금지된 전역 Collection/DocumentSpace CRUD가 없다.

## Planning Review

최종 초안은 두 요구사항 문서와 실제 Journal 구현을 대조했다. 숫자 ID 예시, 세부 제한값, 게시/재시도, 권한 경계 및 부모 삭제의 지연 Binary 정리는 위 승인 항목으로 분리했다. FileStorage→LocalFileStorage, 논리 objectKey/물리 Root 분리, Root 외부 설정, Docker 영속 mount와 파일시스템 오류 검증으로 Storage 계획 전체를 대조했다. 기존 Journal→DocumentSpace→Document→Attachment 구조, API/DTO, Backend Streaming, Migration backfill, TEMP 양방향 전이, Journal Hard Delete 및 보상/재시도 설계는 유지했다. 외부 Storage 제품·클라이언트·이미지·전용 환경변수에 의존하는 구현/테스트 항목은 제거했고 SeaweedFS/S3-compatible 연동은 후속 PLAN 대상으로만 남겼다. 제외된 Domain 연결·Frontend·Block 해석·미래 기능은 구현 범위에 넣지 않았다. 이 검토는 Runtime Validation을 수행했다는 의미가 아니다.

## Completion

The Plan may be changed to `completed` only when every required session item and applicable validation item is checked, and no unresolved blocker prevents the Goal from being satisfied. If execution cannot continue, leave incomplete items unchecked and record the blocker before stopping.

현재 상태는 **proposed**다. 사용자 지시에 따라 Storage 계획만 수정했으며 구현 착수 승인·구현·Migration·Runtime 테스트는 아직 수행하지 않았다. 작성만으로 완료 인덱스에 추가하거나 Session checkbox를 체크하지 않는다. 사용자의 명시적 승인 전 `active` 전환 및 구현 시작을 금지한다.
