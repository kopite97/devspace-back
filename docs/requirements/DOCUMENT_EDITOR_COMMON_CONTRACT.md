# Document Editor — Common Domain & API Contract

> Current extension: approved [PLAN-0016](../plans/PLAN-0016-document-attachment-registry-restoration.md) takes precedence for attachment restoration and API/OpenAPI corrections. See the PLAN-0016 contract section below.

## 1. 문서 목적

본 문서는 Document Editor 구현을 위한 Frontend / Backend 공통 Domain 및 API 계약이다.

1차 구현의 기준은 [PLAN-0015](../plans/PLAN-0015-document-editor-phase-one.md)이며, 첨부 Registry 복원과 API 명세 보완은 승인된 [PLAN-0016](../plans/PLAN-0016-document-attachment-registry-restoration.md)을 우선한다. 본 문서는 두 PLAN의 현재 Domain/API 계약을 Frontend와 Backend가 공유하도록 반영한다.

이번 문서 정합성 반영은 구현 착수나 완료를 의미하지 않는다. PLAN의 실행 상태와 세션 검증은 별도로 관리한다.

한쪽에서 임의로 계약을 변경하지 않는다.

---

# 2. 현재 Domain 구조

1차 구현:

```text
Journal
   ↓
DocumentSpace
   ↓
Document
   ↓
Attachment
```

Cardinality:

```text
Journal 1 : 1 DocumentSpace

DocumentSpace 1 : N Document

Document 1 : N Attachment
```

---

# 3. DocumentSpace의 역할

`DocumentSpace`는 범용 Document Collection이다.

현재는 Journal이 사용한다.

향후 다른 Domain도 같은 방식으로 연결할 수 있다.

예:

```text
Project
   ↓
DocumentSpace

Milestone
   ↓
DocumentSpace
```

현재는 해당 연동을 구현하지 않는다.

---

# 4. 의존성 방향

허용:

```text
Journal → DocumentSpace

Document → DocumentSpace

Attachment → Document
```

금지:

```text
DocumentSpace → Journal

Document → Journal
```

Document/DocumentSpace는 자신을 사용하는 상위 Domain의 종류를 알지 않는다.

---

# 5. DocumentSpace Model

```text
DocumentSpace
────────────────
id
createdAt
updatedAt
```

1차에서는 다음 Generic Owner 필드를 사용하지 않는다.

```text
ownerType
ownerId
```

상위 Domain이 `documentSpaceId`를 참조한다.

---

# 6. Journal Model 연동

기존 Journal Domain에 DocumentSpace 참조가 추가된다.

논리적 구조:

```text
Journal
────────────────
id
...
documentSpaceId
```

정확한 Entity Mapping은 기존 Backend Architecture를 따른다.

Journal의 `documentSpaceId`는 UUID, NOT NULL, UNIQUE FK다. 기존 Journal마다 Migration으로 별도 Space를 backfill하고, 신규 Journal과 Space는 같은 Transaction에서 생성한다. 기존 body를 Document로 변환하거나 동기화하지 않으며 생성 replay로 Space를 중복 생성하지 않는다.

---

# 7. Document Model

```text
Document
────────────────
id
documentSpaceId
title
content
status
authorId
createdAt
updatedAt
publishedAt
```

`documentSpaceId NOT NULL`.

---

# 8. Attachment Model

```text
Attachment
────────────────
id
documentId
originalFilename
objectKey
contentType
size
status
createdBy
createdAt
updatedAt
```

`documentId NOT NULL`.

---

# 9. Document Status

```text
DRAFT
PUBLISHED
```

전이:

```text
Create
 ↓
DRAFT
 ↓
Publish
 ↓
PUBLISHED
```

1차에서는 Unpublish 기능을 정의하지 않는다.

PUBLISHED 문서도 PUT으로 저장할 수 있다. 이때 status와 최초 publishedAt을 유지한다. Publish 재호출은 200으로 같은 게시 상태를 반환하며 추가 변경하지 않는다.

---

# 10. Attachment Status

```text
TEMP
ATTACHED
```

전이:

```text
Upload
 ↓
TEMP
 ↓
Document Save attachmentIds 포함
 ↓
ATTACHED
```

제거:

```text
ATTACHED
 ↓
Document Save attachmentIds에서 제외
 ↓
TEMP
 ↓
Grace Period
 ↓
Cleanup
```

---

# 11. Document Content

`content`는 BlockNote의 Document JSON을 그대로 사용한다.

예:

```json
[
  {
    "id": "block-id",
    "type": "paragraph",
    "props": {},
    "content": [
      {
        "type": "text",
        "text": "Hello",
        "styles": {}
      }
    ],
    "children": []
  }
]
```

실제 Schema는 설치된 BlockNote 버전의 `editor.document`를 기준으로 한다.

Backend는 자체 Block Schema를 정의하지 않는다.

content는 JSON 문자열이 아닌 JSON array다. 내부 Block 필드/타입은 해석·정규화·삭제하지 않으며 JSONB의 의미상 값과 배열 순서를 보존한다. Object key 순서나 공백의 byte 단위 보존은 보장하지 않는다.

---

# 12. Journal Document Collection API

Document 생성 및 목록은 Journal Context에서 수행한다.

개념 API:

```http
POST /api/v2/journals/{journalId}/documents

GET /api/v2/journals/{journalId}/documents
```

기존 Journal API와 동일하게 `/api/v2`를 사용한다. 다른 feature의 API 버전은 변경하지 않는다.

---

# 13. Generic Document 생성 API

1차 Public API에서 다음 맥락 없는 생성 Endpoint를 제공하지 않는다.

```http
POST /api/v2/documents
```

Document 생성은 Journal의 DocumentSpace를 통해 수행한다.

---

# 14. Generic Document 목록 API

1차 사용자 기능에는 다음 전역 목록 Endpoint를 제공하지 않는다.

```http
GET /api/v2/documents
```

Document 목록은 Journal Context에서 조회한다.

---

# 15. Document Detail API

Document 생성 이후에는 `documentId`로 공용 Editor API를 사용할 수 있다.

```http
GET    /api/v2/documents/{documentId}

PUT    /api/v2/documents/{documentId}

DELETE /api/v2/documents/{documentId}

POST   /api/v2/documents/{documentId}/publish
```

---

# 16. Create Document Request

개념:

```json
{
  "title": "",
  "content": []
}
```

Client가 다음 값을 직접 지정하지 않는다.

```text
documentSpaceId
authorId
status
createdAt
updatedAt
publishedAt
```

Backend가 `journalId`로 Journal의 DocumentSpace를 Resolve한다.

---

# 17. Create Document Response

```json
{
  "id": "00000000-0000-4000-8000-000000000123",
  "documentSpaceId": "00000000-0000-4000-8000-000000000100",
  "title": "",
  "content": [],
  "attachmentIds": [],
  "status": "DRAFT",
  "authorId": "00000000-0000-4000-8000-000000000010",
  "createdAt": "2026-09-22T07:30:00Z",
  "updatedAt": "2026-09-22T07:30:00Z",
  "publishedAt": null,
  "attachments": []
}
```

`documentSpaceId`는 읽기 정보다.

Client가 변경하지 않는다.

---

# 18. Document Detail Response

```json
{
  "id": "00000000-0000-4000-8000-000000000123",
  "documentSpaceId": "00000000-0000-4000-8000-000000000100",
  "title": "오늘의 개발 기록",
  "content": [],
  "attachmentIds": [
    "00000000-0000-4000-8000-000000000501",
    "00000000-0000-4000-8000-000000000502"
  ],
  "status": "DRAFT",
  "authorId": "00000000-0000-4000-8000-000000000010",
  "createdAt": "2026-09-22T07:30:00Z",
  "updatedAt": "2026-09-22T07:40:00Z",
  "publishedAt": null,
  "attachments": [
    {
      "id": "00000000-0000-4000-8000-000000000501",
      "documentId": "00000000-0000-4000-8000-000000000123",
      "originalFilename": "example.txt",
      "contentType": "text/plain",
      "size": 5,
      "status": "ATTACHED",
      "contentUrl": "/api/v2/attachments/00000000-0000-4000-8000-000000000501/content",
      "createdAt": "2026-09-22T07:30:00Z"
    },
    {
      "id": "00000000-0000-4000-8000-000000000502",
      "documentId": "00000000-0000-4000-8000-000000000123",
      "originalFilename": "example.txt",
      "contentType": "text/plain",
      "size": 5,
      "status": "ATTACHED",
      "contentUrl": "/api/v2/attachments/00000000-0000-4000-8000-000000000502/content",
      "createdAt": "2026-09-22T07:30:00Z"
    }
  ]
}
```

`attachmentIds`는 Document Table 컬럼이 아니다.

Attachment Relation에서 파생되는 API DTO 값이다.

현재 ATTACHED인 row의 UUID를 안정적인 UUID 순서로 반환한다. Block 배치 순서는 content가 담당한다. 예시의 빈 content와 attachmentIds는 별개 정보이며 Backend는 Block을 해석해 두 값을 강제로 맞추지 않는다.

---

# 19. Update Document

```http
PUT /api/v2/documents/{documentId}
```

Request:

```json
{
  "title": "오늘의 개발 기록",
  "content": [],
  "attachmentIds": ["00000000-0000-4000-8000-000000000501", "00000000-0000-4000-8000-000000000502"]
}
```

Client가 다음 값은 변경하지 않는다.

```text
documentSpaceId
status
authorId
createdAt
publishedAt
```

---

# 20. content와 attachmentIds

## content

문서의 화면 구조를 의미한다.

```text
Heading
Paragraph
Table
Image 위치
File 위치
...
```

## attachmentIds

현재 Document가 실제 사용하는 Backend Attachment 집합이다.

Attachment Lifecycle 관리의 Source of Truth다.

---

# 21. Attachment 일관성

Frontend:

> 서비스 Attachment를 사용하는 Block이 현재 Document에 존재하면 해당 ID를 `attachmentIds`에 포함한다.

Backend:

> 전달받은 모든 Attachment가 실제 존재하며 해당 Document에 소속되고 사용자에게 접근 권한이 있는지 검증한다.

Backend는 BlockNote JSON 안을 분석하여 Attachment 관계를 판단하지 않는다.

---

# 22. Autosave

Frontend:

```text
Editor Change
 ↓
Debounce
 ↓
PUT Document
```

동일 Document에 대한 Save Request는 직렬화한다.

Backend는 각 PUT을 해당 시점 Document 전체 상태로 취급한다.

Document에 revision/If-Match/필수 Idempotency-Key를 추가하지 않는다. 여러 Client의 오래된 저장이 경합하면 마지막 commit이 이긴다. 생성·Upload timeout 이후 자동 재시도는 중복을 만들 수 있다. 기존 Journal 생성의 멱등 replay 계약은 유지한다.

---

# 23. Publish

```http
POST /api/v2/documents/{documentId}/publish
```

Frontend는 최신 PUT이 성공한 이후 Publish를 수행한다.

```text
Latest Editor State
 ↓
Save Success
 ↓
Publish
```

최신 PUT 성공 전에 Publish하거나 저장을 병렬 실행하면 최신 편집 내용이 게시되었다고 보장하지 않는다.

---

# 24. Journal Document List

개념:

```http
GET /api/v2/journals/{journalId}/documents
```

목록에서는 전체 `content`와 `attachmentIds`를 반환하지 않는다.

예:

```json
{
  "items": [
    {
      "id": "00000000-0000-4000-8000-000000000123",
      "title": "오늘의 개발 기록",
      "status": "PUBLISHED",
      "authorId": "00000000-0000-4000-8000-000000000010",
      "createdAt": "2026-09-22T07:30:00Z",
      "updatedAt": "2026-09-22T07:40:00Z",
      "publishedAt": "2026-09-22T07:40:00Z"
    }
  ],
  "total": 1,
  "nextCursor": null
}
```

목록은 `{items,total,nextCursor}`를 사용한다. `limit`은 기본 20, 범위 1..100이며 `cursor`는 선택 사항이다. DRAFT/PUBLISHED를 모두 포함하고 `createdAt DESC,id DESC`로 정렬한다. 검색·상태 필터는 1차에 제공하지 않는다.

서명된 cursor는 resource namespace/Workspace/Journal/Space/limit에 묶이며 다른 맥락에서 재사용하거나 변조하면 거부한다. count와 page는 같은 snapshot에서 읽되 페이지 간 고정 snapshot은 보장하지 않는다.

---

# 25. Attachment Upload

```http
POST /api/v2/attachments
Content-Type: multipart/form-data
```

Form:

```text
documentId = 00000000-0000-4000-8000-000000000123
file       = <binary>
```

Document가 존재하기 전에 Attachment를 Upload할 수 없다.

---

# 26. Attachment Upload Response

```json
{
  "id": "00000000-0000-4000-8000-000000000501",
  "documentId": "00000000-0000-4000-8000-000000000123",
  "originalFilename": "architecture.png",
  "contentType": "image/png",
  "size": 245813,
  "status": "TEMP",
  "contentUrl": "/api/v2/attachments/00000000-0000-4000-8000-000000000501/content",
  "createdAt": "2026-09-22T07:42:00Z"
}
```

Frontend는 최소 다음 값을 추적한다.

```text
id
contentUrl
```

---

# 27. Attachment Content

```http
GET /api/v2/attachments/{attachmentId}/content
```

이 Endpoint는 안정적인 Application URL이다.

Backend Streaming으로 전달한다. Storage 직접 URL이나 Presigned URL로 Redirect하지 않는다.

소유자의 TEMP도 미리보기할 수 있으나 삭제 claim 이후에는 404를 반환한다. `no-store`, `X-Content-Type-Options: nosniff`, 검증된 Content-Type, 안전한 Content-Disposition/Content-Length를 적용한다. PNG/JPEG는 inline, PDF/TXT는 attachment download다. Range와 conditional cache는 1차 범위 밖이다.

전송 시작 이후 장애는 JSON 오류로 바꿀 수 없으므로 연결 종료와 내부 로그로 처리한다. 삭제와 경합할 때 이미 열린 stream은 완료될 수 있다.

---

# 28. Document에 저장하지 않는 Storage 정보

다음 값은 Document JSON에 영구 저장하지 않는다.

```text
Storage Root / 절대 파일 경로
Storage 직접 접근 URL
Object Key
Expiring Presigned URL
```

Backend는 Storage 정보를 생성·주입하지 않고 안정적인 Application URL만 제공한다. Frontend는 이를 저장하며 Backend는 Block 내부 URL의 의미를 분석해 강제하지 않는다.

---

# 29. Attachment Delete

```http
DELETE /api/v2/attachments/{attachmentId}
```

TEMP:

```text
삭제 허용
```

ATTACHED:

```text
409 Conflict
ATTACHMENT_IN_USE
```

먼저 Document에서 제거하고 Save하여 TEMP로 전환한다.

TEMP 직접 삭제의 성공은 **200 `{deletedId}`**이며 파일 제거(정상 Root에서 이미 없는 경우 포함)와 Attachment Metadata 삭제가 완료되었음을 뜻한다. 요청 body는 없다.

Storage 실패에는 **503 STORAGE_UNAVAILABLE**을 반환하고 영속 삭제 claim과 재시도 작업을 남긴다. 실패 응답 뒤에도 Backend의 삭제 재시도는 계속될 수 있다. claim 이후 재연결 또는 중복 직접 삭제는 **409 ATTACHMENT_DELETION_PENDING**, content 조회는 404다. 정리가 끝나 존재하지 않는 Attachment는 404다. 권한 확인이 상태 오류보다 우선한다.

---

# 30. Attachment Reconciliation

기존 상태:

```text
00000000-0000-4000-8000-000000000501 ATTACHED
00000000-0000-4000-8000-000000000502 ATTACHED
00000000-0000-4000-8000-000000000503 TEMP
```

Document Save의 attachmentIds 필드 발췌 (실제 PUT에는 title/content도 필수):

```json
{
  "attachmentIds": ["00000000-0000-4000-8000-000000000501", "00000000-0000-4000-8000-000000000503"]
}
```

결과:

```text
00000000-0000-4000-8000-000000000501 ATTACHED
00000000-0000-4000-8000-000000000502 TEMP
00000000-0000-4000-8000-000000000503 ATTACHED
```

TEMP↔ATTACHED 전환으로 LocalFileStorage 파일이나 논리 objectKey를 이동·변경하지 않는다.

상태가 바뀐 Attachment의 updatedAt만 갱신하며 detach 시점부터 Grace Period를 계산한다. 변경 없는 ATTACHED/TEMP의 updatedAt은 유지한다. TEMP는 삭제 claim 전까지 다시 ATTACHED로 연결할 수 있다.

Cleanup 기본값은 Grace Period 24시간, fixed delay 10분, batch 100개다. `status=TEMP AND updatedAt < cutoff`인 항목을 `(updatedAt,id)` 순서로 검사하므로 cutoff와 같은 시각은 이번 대상이 아니다. 삭제 claim 확정 후 Transaction 밖에서 파일을 제거하고 Metadata를 정리한다. Save가 먼저 완료되면 Cleanup은 건너뛰고 claim이 먼저 확정되면 Save 전체를 409로 거부한다. 설정은 외부화한다.

---

# 31. Document 삭제

```http
DELETE /api/v2/documents/{documentId}
```

삭제 시:

```text
Document
 ↓
Attachments
 ↓
LocalFileStorage Objects
```

가 함께 정리되어야 한다. Document만 삭제할 때 Journal의 DocumentSpace는 유지한다.

**성공 응답: 200 `{deletedId}`.** 이는 Document와 Attachment Metadata의 DB Hard Delete 및 관련 모든 Binary의 삭제 작업 영속화가 하나의 Transaction으로 commit되었음을 뜻한다. 모든 Binary의 물리적 삭제가 응답 전에 완료되었다는 뜻은 아니다. 요청 body는 없다.

```json
{
  "deletedId": "00000000-0000-4000-8000-000000000123"
}
```

- 모든 TEMP/ATTACHED objectKey의 삭제 작업을 확보한 뒤 Metadata와 Document를 제거한다. 기존 claim은 재사용하고 작업 기록은 부모 삭제로 유실되지 않는다.
- 작업 영속화나 부모 DB 삭제가 실패하면 전체 Transaction을 rollback한다. 성공 응답을 반환하지 않으며 Binary를 먼저 지우지 않는다.
- commit 후 즉시 제한된 양의 파일 정리를 시도한다. Storage 장애나 미완료 작업은 Scheduler가 backoff로 재시도하고 프로세스 재기동 후에도 이어서 처리한다. 정상 경로와 장애 복구 후 모두 최종 파일 제거를 검증한다.
- 파일은 물리적으로 잠시 남을 수 있지만 삭제된 Metadata를 통한 새로운 API 접근은 404다. 이미 열린 stream은 완료될 수 있다. Frontend는 200 이후 목록·Editor에서 문서를 제거할 수 있으며 이를 물리 파일 삭제 완료 표시로 사용하지 않는다.
- 정상 Root에서 파일이 이미 없으면 삭제 성공으로 처리한다. Root/mount/권한 오류는 파일 부재로 간주하지 않는다. 실패 작업을 자동 포기하거나 삭제하지 않고 재시도 횟수·오류를 관측 가능하게 남긴다. 고정된 물리 삭제 완료 시간은 보장하지 않는다.
- 파일 제거 후 Metadata/작업 정리에 실패해도 영속 작업으로 재개한다. 이미 삭제된 Document의 반복 삭제·조회는 404이며 같은 삭제를 다시 성공한 것으로 응답하지 않는다.

---

# 32. Journal 삭제

Journal이 DocumentSpace를 독점 소유한다.

따라서 Journal 삭제 Lifecycle은 다음 관계를 고려한다.

```text
Journal
 ↓
DocumentSpace
 ↓
Documents
 ↓
Attachments
 ↓
LocalFileStorage Objects
```

기존 Journal의 ownership/revision 검사와 Hard Delete 정책을 유지한다. 관련 Binary 정리 작업을 먼저 확보한 뒤 Attachment → Document → Journal → DocumentSpace 순으로 같은 DB Transaction에서 제거한다.

기존 **200 `{deletedId}`** 응답은 이 DB 삭제와 Binary 정리 작업의 영속화 성공을 뜻한다. §31과 동일하게 물리 파일 정리는 장애 시 지연될 수 있고 재시작 후에도 재시도한다. 작업 영속화 실패에는 전체 삭제를 rollback한다. 이미 삭제된 Journal의 반복 요청은 기존 계약대로 404다. 기존 생성 replay가 과거 응답을 반환하더라도 Journal/Space/Document를 부활시키지 않는다.

---

# 33. DocumentSpace 직접 API

현재 Public API로 다음 기능을 제공하지 않는다.

```text
DocumentSpace 생성
DocumentSpace 삭제
DocumentSpace 수정
DocumentSpace 목록
```

DocumentSpace Lifecycle은 상위 Domain 및 Backend Application Service에서 관리한다.

---

# 34. 현재 구현 대상

포함:

```text
DocumentSpace
Journal ↔ DocumentSpace
Document
Attachment
BlockNote JSON
LocalFileStorage
Autosave
DRAFT / PUBLISHED
Journal Document 목록
공용 Document Editor / Viewer
```

---

# 35. 현재 제외 대상

```text
Project ↔ DocumentSpace
Milestone ↔ DocumentSpace
Resource ↔ DocumentSpace

Collaboration
Comment
Mention
Template
History
Revision
Share Link
Kanban
Calendar
AI
Custom Widget
GitHub Integration
Server Integration
Kubernetes Integration
```

현재 필요하지 않은 연결과 기능을 미리 구현하지 않는다.

---

# 36. 향후 Domain 연결 원칙

향후 새로운 Domain에 Document 기능이 필요하면:

```text
NewDomain
   ↓
DocumentSpace
   ↓
Document
   ↓
Attachment
```

구조를 재사용한다.

Document에 `journalId`, `projectId` 등 Domain 전용 FK를 추가하지 않는다.

---

# 37. Environment Contract

환경 의존 설정:

```text
PostgreSQL Endpoint
FILE_STORAGE_ROOT
Attachment / Document 크기 제한
TEMP Grace / Cleanup 주기·batch·enabled
Streaming 제한
Profile
```

은 외부 설정으로 관리한다.

Local과 Mini PC 서버에서 동일한 Application Artifact를 사용한다.

Root는 명시적으로 준비한 영속 절대 경로다. Root/초기화 표식·권한·mount를 검증하고 누락 시 임시 경로나 빈 저장소로 fallback하지 않는다. Root 변경은 파일을 자동 이동시키지 않으므로 환경 이동·복구 시 DB와 파일을 함께 보존·복원한다.

---

# 38. Local 개발 환경

```text
React
→ Local

Spring Boot
→ Local

PostgreSQL
→ Local 또는 Docker Compose

LocalFileStorage
→ 외부 설정으로 지정한 영속 디렉터리
```

Frontend는 인증된 Application Content URL로만 파일에 접근하며 로컬 파일 경로나 Storage에 직접 접근하지 않는다.

Application을 Docker로 실행하면 FILE_STORAGE_ROOT에 persistent named volume 또는 bind mount를 연결한다. 컨테이너 임시 writable layer에 영속 파일을 저장하지 않는다. 별도 Storage 서버·SDK는 추가하지 않는다.

---

# 39. 1차 통합 성공 시나리오

최소 다음 흐름이 성공해야 한다.

```text
1. Journal 조회

2. 해당 Journal의 Document 목록 조회

3. 새 Document 생성

4. Journal의 DocumentSpace에 DRAFT Document 저장

5. 공용 Editor 진입

6. 제목 / BlockNote Content 작성

7. Attachment Upload
   → TEMP
   → LocalFileStorage 저장

8. Autosave
   → attachmentIds 포함
   → ATTACHED

9. 새로고침

10. 동일한 Content 및 Attachment 복원

11. Publish

12. Journal Document 목록에서 게시글 확인

13. Read-only Viewer 조회

14. Attachment Block 제거

15. Save
    → ATTACHED → TEMP

16. Grace Period 후 Cleanup

17. Document 삭제
    → DB Hard Delete + Binary 정리 작업 영속화
    → 정상 경로 즉시 정리 시도, 장애 시 지연 재시도

18. Journal 삭제 시
    → DocumentSpace Lifecycle 정상 처리
```

Frontend와 Backend는 PLAN-0015와 승인된 후속 PLAN-0016을 기준으로 이 계약의 흐름을 검증한다. 지연 정리·재시작 복구 검증은 §31의 성공 의미를 따른다.

---

# 40. 공통 HTTP·Validation 계약

모든 리소스 ID, authorId/createdBy, attachmentIds 원소와 deletedId는 UUID 문자열이다. 감사 시각은 서버가 결정하는 UTC ISO-8601이며 `publishedAt`은 응답에 항상 존재하는 nullable 필드다.

모든 API는 기존 Session 인증·소유권 검사·no-store를 사용한다. 변경 API는 X-CSRF-Token/Origin 정책을 따른다. archived Project 아래 기존 Journal의 Document 작성·편집·게시·삭제를 허용한다. authorId/createdBy는 Audit이며 권한 판단을 대신하지 않는다.

| Method / Path | 입력 | 성공 응답 |
| --- | --- | --- |
| POST `/api/v2/journals/{journalId}/documents` | `{title,content}` | 201 DocumentResponse, DRAFT, attachmentIds=[], publishedAt=null |
| GET `/api/v2/journals/{journalId}/documents` | limit, cursor | 200 `{items,total,nextCursor}` |
| GET `/api/v2/documents/{documentId}` | UUID | 200 DocumentResponse |
| PUT `/api/v2/documents/{documentId}` | `{title,content,attachmentIds}` 전체 상태 | 200 DocumentResponse |
| DELETE `/api/v2/documents/{documentId}` | UUID, body 없음 | 200 `{deletedId}`; DB 삭제와 Binary 정리 작업 영속화 |
| POST `/api/v2/documents/{documentId}/publish` | body 없음 | 200 DocumentResponse |
| POST `/api/v2/attachments` | documentId 텍스트 field + 단일 file part | 201 AttachmentUploadResponse, TEMP |
| GET `/api/v2/attachments/{attachmentId}/content` | UUID | 200 Binary stream |
| DELETE `/api/v2/attachments/{attachmentId}` | UUID, body 없음 | 200 `{deletedId}`; 파일 제거 후 Metadata 삭제 완료 |

생성·Upload의 201과 PUT·최초 Publish의 200은 해당 DB 변경 commit 이후에만 반환한다. PUT은 Document와 Attachment reconciliation을 한 Transaction으로 저장하고 하나라도 검증에 실패하면 모두 rollback한다. 동일 값 PUT은 updatedAt/counter를 갱신하지만 반복 Publish는 변경하지 않는다. 부모 삭제의 200과 TEMP 직접 삭제의 200은 위 표 및 §29·§31·§32처럼 완료 범위가 다르다.

| 입력 | 기준 |
| --- | --- |
| title | 필수 non-null String, 0..200 UTF-16 units, 빈 값 허용·원문 보존 |
| content | 필수 JSON array, 빈 배열 허용, UTF-8 직렬화 기준 최대 1 MiB, nesting depth 최대 100 |
| attachmentIds | PUT 필수 non-null UUID 배열, null 원소·중복 불가, 최대 100. 생략은 오류, []는 전체 detach |
| file | 0 byte 거부, 최대 10 MiB. multipart 요청 전체 상한 11 MiB |
| 허용 형식 | PNG(.png), JPEG(.jpg/.jpeg), PDF(.pdf), UTF-8 plain text(.txt). 선언 MIME·확장자·signature/UTF-8 검증 |
| 거부 형식 | SVG/HTML/JS, 실행 파일·archive 및 allowlist 밖 형식 |
| filename | display metadata 전용, 최대 255 UTF-16 units. path component/제어문자/CRLF를 제거·거부하고 안전하게 인코딩 |

Document JSON 요청 전체에도 parser/body 한도를 적용한다. content byte 상한과 요청 전체 상한은 서로 다르다. 환경별 제한은 Backend 외부 설정과 OpenAPI에 일치시킨다.

허용된 writable field만 받으며 id/documentSpaceId/authorId/createdBy/status/objectKey/audit 등 서버 필드는 거부한다. top-level unknown/duplicate key, 명시적 null, 잘못된 타입·UUID, 반복·미지원 query/part를 거부한다. content 내부 Block은 이 필드 whitelist로 제한하지 않는다.

오류는 `{code,message,fieldErrors,requestId}` 공통 형식이다. 권한 확인을 상태 conflict보다 먼저 하고 타인 리소스와 없는 리소스는 같은 404로 처리한다.

| HTTP / code | 의미 |
| --- | --- |
| 400 VALIDATION_ERROR | JSON/UUID/필수 field/첨부 ID 중복·개수/파일 등 입력 오류 |
| 400 INVALID_CURSOR | 변조·다른 Context·잘못된 cursor |
| 401 AUTH_REQUIRED | 인증 필요 |
| 403 ACCOUNT_DISABLED / CSRF_INVALID | 기존 비활성 계정·CSRF 정책; Origin도 기존 매핑 유지 |
| 404 RESOURCE_NOT_FOUND | 없음 또는 접근 불가; 타 Document의 attachmentIds도 동일 처리 |
| 409 ATTACHMENT_IN_USE | ATTACHED 직접 삭제 거부 |
| 409 ATTACHMENT_DELETION_PENDING | 삭제 claim이 있는 TEMP의 연결·중복 직접 삭제 거부 |
| 413 PAYLOAD_TOO_LARGE | 파일·multipart·JSON 요청 크기 초과; Servlet/parser 오류도 공통 형식 |
| 415 UNSUPPORTED_MEDIA_TYPE | JSON/multipart 등 요청 media type 불일치; 파일 allowlist 위반은 400 |
| 503 STORAGE_UNAVAILABLE | Upload/Content/TEMP 직접 삭제의 Storage 실패 |
| 500 INTERNAL_ERROR | 예상하지 못한 DB/내부 오류 |

기존 workspace counter 한도 등의 오류 코드는 유지한다. Stream 전송 시작 후 오류에는 이미 보낸 Binary를 JSON 오류 응답으로 대체하지 않는다.


## PLAN-0016: Attachment Registry restoration

The approved PLAN-0016 supplements the corresponding PLAN-0015 contract.

Document create/detail/PUT/publish responses add required non-null `attachments`; Journal collection summaries remain unchanged. Each item requires all eight non-null fields: `id, documentId, originalFilename, contentType, size, status, contentUrl, createdAt`. IDs are UUIDs, size is bytes, createdAt is UTC ISO-8601, contentUrl is `/api/v2/attachments/{id}/content`. Restored items are ATTACHED; upload responses remain TEMP.

`attachmentIds` and `attachments.map(id)` use the same snapshot and row set in UUID order. Return all saved attachments, at most 100, without pagination. TEMP (unsaved, detached, or claimed for deletion) is excluded and does not consume this saved relationship limit. Deleted metadata is absent. Never infer or rewrite references inside content. PUT remains `{title,content,attachmentIds}`; sending response-only attachments is a 400.

Validate session user, Workspace, Journal/Project and Document ownership before reading metadata. GET uses REPEATABLE_READ. A snapshot preceding concurrent deletion may still return metadata; subsequent content requests can return 404. ATTACHED plus deletion claim, or over 100 ATTACHED rows, is 500 INTERNAL_ERROR, never silent truncation. Frontend must preserve editing state and stop saving on restoration failure, never substitute an empty registry.

Metadata restoration does not check physical storage. A missing binary under a healthy root returns 404 from the content API; root/storage failures return 503. Unsaved TEMP restoration is outside scope.

PUT validates existence and Document membership of the entire well-formed ID list before checking deletion claims. Missing/deleted/other-Document (including same owner)/foreign attachments return 404 RESOURCE_NOT_FOUND. Only after membership passes, a deletion claim returns 409 ATTACHMENT_DELETION_PENDING. Mixed inputs return 404 regardless of input/UUID order. Invalid UUID/type/null/duplicates/over 100 IDs remain 400 VALIDATION_ERROR. Failed PUT rolls back all state/counters.

Default limits: parsed content serialized as UTF-8 <= 1,048,576 bytes; whole JSON request <= 1,114,112 bytes (content limit + 65,536); one file <= 10,485,760 bytes; whole Multipart <= 11,534,336 bytes. Oversize is 413 PAYLOAD_TOO_LARGE. Nesting depth remains 100; invalid depth/file format/empty file is 400. Unsupported HTTP request media type is 415 UNSUPPORTED_MEDIA_TYPE. Existing DOCUMENT_CONTENT_MAX_BYTES / ATTACHMENT_MAX_BYTES / ATTACHMENT_REQUEST_MAX_BYTES configure limits; string maxLength does not represent byte limits.

Direct attachment deletion: ATTACHED returns 409 ATTACHMENT_IN_USE; an already claimed TEMP returns 409 ATTACHMENT_DELETION_PENDING; deleted/absent returns 404 RESOURCE_NOT_FOUND; storage failure returns 503 STORAGE_UNAVAILABLE. Content after a deletion claim is 404. Failure after streaming starts closes the connection instead of replacing it with JSON.

Document/Attachment DELETE returns 200 with required non-null UUID `deletedId`, never `deletedIt`. Document success means metadata deletion and durable binary cleanup scheduling; TEMP direct deletion success means both binary and metadata removal. Durable cleanup can continue after a storage failure response.
