# Document Editor — Backend Requirements

> Current extension: approved [PLAN-0016](../plans/PLAN-0016-document-attachment-registry-restoration.md) takes precedence for attachment restoration and API/OpenAPI corrections. See the PLAN-0016 contract section below.

## 1. 문서 목적

본 문서는 공용 Document Editor Backend의 요구사항 및 설계 지침이다. 1차 구현은 [PLAN-0015](../plans/PLAN-0015-document-editor-phase-one.md)를 따르며, 첨부 Registry 복원과 API 명세 보완은 승인된 [PLAN-0016](../plans/PLAN-0016-document-attachment-registry-restoration.md)을 우선한다.

본 문서 자체는 구현 PLAN이 아니다.

Backend 작업자는 PLAN-0015의 Repository Review와 세션 순서를 따르고 구현 시 현재 코드를 재확인한다. DTO·제한값·HTTP 응답은 [공통 계약](DOCUMENT_EDITOR_COMMON_CONTRACT.md)의 §40을 함께 따른다. 이번 문서 반영은 구현 착수나 완료를 의미하지 않는다.

---

# 2. 핵심 Architecture

Document 시스템은 다음 범용 구조를 사용한다.

```text
Owner Domain
    ↓
DocumentSpace
    ↓
Document
    ↓
Attachment
```

현재 실제 연결 대상:

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

# 3. DocumentSpace의 목적

`DocumentSpace`는 Document를 묶기 위한 범용 Collection Domain이다.

향후 다음과 같은 다른 Domain도 동일한 구조를 사용할 수 있다.

```text
Project → DocumentSpace
Milestone → DocumentSpace
OtherDomain → DocumentSpace
```

그러나 현재 1차 구현에서는 `journals`만 연결한다.

Project/Milestone 연동을 미리 구현하지 않는다.

---

# 4. 의존성 방향

현재:

```text
Journal → DocumentSpace
Document → DocumentSpace
Attachment → Document
```

DocumentSpace는 Journal을 알지 않는다.

Document 역시 Journal을 알지 않는다.

금지:

```text
Document → Journal
DocumentSpace → Journal
```

Document Domain에는 다음과 같은 상위 Domain 전용 필드를 넣지 않는다.

```text
journalId
projectId
milestoneId
ownerType
ownerId
```

---

# 5. DocumentSpace Persistence Model

최소 구조:

```text
DocumentSpace
────────────────
id
createdAt
updatedAt
```

현재 요구사항에 필요하지 않은 메타데이터는 추가하지 않는다.

특히 다음과 같은 Generic Polymorphic Reference를 만들지 않는다.

```text
ownerType
ownerId
```

---

# 6. Journal과 DocumentSpace

현재 `journals` Domain의 각 Journal은 자신의 DocumentSpace 하나를 독점적으로 소유한다.

개념:

```text
Journal (UUID)
   ↓
DocumentSpace (UUID)
```

Journal 쪽에서 `documentSpaceId`를 참조한다.

기존 scalar UUID 관계 Convention을 따라 Journal에 `document_space_id UUID NOT NULL UNIQUE FK`를 추가한다.

---

# 7. 기존 Journal 데이터

Migration backfill을 사용한다. 기존 Journal마다 별도 UUID의 DocumentSpace를 생성하고 FK·UNIQUE·NOT NULL로 독점 소유 관계를 보장한다. 조회 시 Lazy 생성은 하지 않는다.

Space의 audit는 해당 Journal audit에서 초기화한다. Journal의 id/body/title/entryDate/revision/audit/Project 관계 및 생성 replay bytes/hash/expiry는 유지한다. 기존 body로 Document를 만들지 않으므로 초기 문서 목록은 비어 있다.

실행 시 V18 이후 가용 Migration 번호를 재확인하고 새 Migration만 추가한다. Journal FK/backfill/NOT NULL과 신규 writer 연결은 같은 세션에서 적용하고 구버전 writer를 종료한 coordinated restart를 전제로 한다. 격리 upgrade에서 누락·중복·고아와 기존 데이터 보존을 검증한다.

---

# 8. Journal 생성

Journal의 실제 신규 생성 분기에서 Space와 Journal을 같은 Transaction으로 저장한다. workspace dataRevision 증가와 생성 replay 저장도 기존처럼 한 번 처리하고 어느 단계의 실패도 Space까지 rollback한다.

생성 replay hit에는 Space를 만들지 않는다. 삭제된 Journal의 과거 201 snapshot을 재처리해도 Journal/Space/Document를 부활시키지 않는다. 기존 Journal Response/Snapshot에 documentSpaceId를 추가하지 않는다.

Document 생성·삭제 등 컬렉션 변경은 Space.updatedAt을 갱신한다. Document 저장은 기존 Journal body/revision/audit를 변경하지 않는다.

---

# 9. Document Persistence Model

```text
Document
────────────────────
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

`documentSpaceId`는 `NOT NULL`.

관계:

```text
DocumentSpace 1 : N Document
```

---

# 10. Document Content

본문은 BlockNote Document JSON이다.

PostgreSQL:

```text
content JSONB
```

Backend는 BlockNote 각 Block을 Java Domain Model로 만들지 않는다.

다음 구조는 피한다.

```text
ParagraphBlock
HeadingBlock
ImageBlock
TableBlock
...
```

기존 JSONB 선례에 따라 opaque JSON String 매핑을 우선 재사용하되 API에는 JSON 문자열이 아닌 array를 반환한다. 최상위 array와 JSON 문법·자원 한도만 검사하며 내부 Block을 해석·정규화·삭제하지 않는다. JSONB의 의미상 값과 배열 순서를 검증하고 object key 순서·공백의 byte 보존은 보장하지 않는다.

---

# 11. Document Status

상태:

```text
DRAFT
PUBLISHED
```

Document 생성 시 항상 DRAFT.

일반 Update API에서 Client가 Status를 변경하지 않는다.

Publish는 별도 Use Case로 처리한다.

PUBLISHED 문서의 PUT은 status와 최초 publishedAt을 유지한다. Publish 재호출은 같은 상태를 반환하며 추가 변경하지 않는다. Unpublish는 제공하지 않는다.

---

# 12. Document 생성

Document 생성은 Journal Context에서 수행한다.

개념 API:

```http
POST /api/v2/journals/{journalId}/documents
```

URI는 기존 Journal Convention과 동일하게 `/api/v2`를 사용한다. Create는 201 DocumentResponse를 반환한다.

처리:

```text
Journal 확인
   ↓
접근 권한 검증
   ↓
Journal.documentSpace Resolve
   ↓
DRAFT Document 생성
```

맥락 없는 다음 API는 제공하지 않는다.

```http
POST /api/v2/documents
```

---

# 13. Document 목록

Document 목록 역시 Journal Context에서 제공한다.

개념:

```http
GET /api/v2/journals/{journalId}/documents
```

DocumentSpace ID를 Frontend가 직접 Query하지 않는다.

Backend가 Journal에서 Space를 Resolve한다.

목록은 `{items,total,nextCursor}`, 기본 limit 20/최대 100, `createdAt DESC,id DESC` 정렬을 사용한다. DRAFT/PUBLISHED를 모두 포함하고 content/attachmentIds는 제외한다. 서명된 cursor와 동일 snapshot의 count/page·소유권 검사를 적용한다.

---

# 14. Document 단건 API

Document 생성 이후에는 전역적으로 유일한 `documentId`를 기준으로 공용 API를 사용할 수 있다.

개념:

```http
GET    /api/v2/documents/{documentId}
PUT    /api/v2/documents/{documentId}
DELETE /api/v2/documents/{documentId}

POST   /api/v2/documents/{documentId}/publish
```

GET/PUT/Publish는 200 DocumentResponse를 반환한다. DELETE는 200 `{deletedId}`이며 §28의 삭제 성공 의미를 따른다. UUID·입력 whitelist·Validation·오류 코드는 공통 계약 §40에 정의한다.

---

# 15. 전역 Document 목록

1차 구현에서는 다음과 같은 전역 목록 API를 제공하지 않는다.

```http
GET /api/v2/documents
```

현재 사용자 목록 진입점은 `journals`이다.

향후 Admin 또는 Search 요구사항이 생길 경우 별도로 검토한다.

---

# 16. DocumentSpace 관리 API

Frontend가 DocumentSpace를 직접 관리하지 않으므로 다음 Public CRUD는 1차에서 제공하지 않는다.

```text
POST   /api/v2/document-spaces
PUT    /api/v2/document-spaces/{id}
DELETE /api/v2/document-spaces/{id}
GET    /api/v2/document-spaces
```

DocumentSpace는 Application 내부 Domain으로 관리한다.

---

# 17. Attachment Persistence Model

```text
Attachment
────────────────────
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

관계:

```text
Document 1 : N Attachment
```

`documentId`는 `NOT NULL`.

---

# 18. Attachment Status

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
Document Save attachmentIds에 포함
 ↓
ATTACHED
```

제거:

```text
ATTACHED
 ↓
attachmentIds에서 제외
 ↓
TEMP
 ↓
Grace Period
 ↓
Cleanup
```

TEMP 상태의 Attachment가 다시 사용되면 ATTACHED로 복귀할 수 있다.

---

# 19. Attachment Source of Truth

Backend는 BlockNote JSON 안의 Image/File Block을 분석하지 않는다.

Document Update Request의:

```text
attachmentIds
```

를 Attachment 관계의 Source of Truth로 사용한다.

Backend는 다음을 검증한다.

* Attachment 존재 여부
* 해당 Document 소속 여부
* 현재 사용자 접근 권한

---

# 20. Document Update와 Reconciliation

개념:

```text
Document 권한 확인
   ↓
attachmentIds 검증
   ↓
title / content 저장
   ↓
요청 ID → ATTACHED
   ↓
기존 ATTACHED 중 빠진 ID → TEMP
```

모든 요청 ID의 존재·동일 Document·권한·삭제 claim 부재를 먼저 검증한다. 하나라도 실패하면 Document·Attachment·audit·counter를 모두 rollback한다. DB 변경은 하나의 Transaction 내에서 처리하고 Storage I/O는 Transaction 밖에서 수행한다.

LocalFileStorage Object를 상태 변경 때마다 이동시키지 않는다.

상태가 바뀐 Attachment의 updatedAt만 갱신한다. detach 시점부터 Grace Period가 시작되며 변경 없는 ATTACHED/TEMP의 updatedAt은 보존한다. attachmentIds는 현재 ATTACHED 관계에서 파생하고 DB 배열 컬럼이나 Block 분석 결과로 저장하지 않는다.

---

# 21. Storage Abstraction

Application 소유 FileStorage는 필요한 put/open/delete만 정의한다. stream/size/contentType과 논리 objectKey를 중립 계약으로 사용하며 Path/File, 물리 Root, 파일시스템 exception은 adapter 밖에 노출하지 않는다.

1차 구현:

```text
LocalFileStorage
```

향후 다른 Storage를 사용할 수 있도록 Interface 수준에서만 확장성을 유지한다.

현재 사용하지 않는 구현체를 미리 만들지 않는다.

기존 Java 파일 I/O를 사용하고 새 Storage 서버·SDK·파일 I/O 라이브러리는 추가하지 않는다. SeaweedFS/S3-compatible 연동은 후속 PLAN의 별도 범위다.

---

# 22. LocalFileStorage

LocalFileStorage에는 Binary File만 저장한다. 외부 설정의 전용 영속 Root를 사용하며 물리 경로 해석은 infrastructure adapter에만 둔다.

PostgreSQL:

```text
Document JSON
Attachment Metadata
Object Key
```

LocalFileStorage:

```text
Actual Binary
```

논리 Object Key:

```text
documents/{documentId}/{uuid}.{ext}
```

Object Key 생성은 Backend 책임이다.

Root·OS drive·절대경로·host·volume 이름과 원래 filename은 Key에 넣지 않는다. Key 재사용·overwrite를 금지한다. 절대 Key, `..`, drive/UNC, 잘못된 구분자와 symlink/junction escape를 차단한다.

같은 Root 파일시스템의 staging에 bounded buffer로 기록하고 완료 후 최종 Key로 원자적으로 공개한다. 필요한 atomic move를 지원하지 않으면 실패로 처리하며 부분 파일을 노출하는 fallback은 하지 않는다. staging은 Content API 대상이 아니다. 경로 변경 경쟁을 막는 실행 계정 권한과 Root 포함 검증을 함께 적용한다.

---

# 23. Stable Content URL

Frontend는 LocalFileStorage Root나 물리 파일 경로를 알지 않는다.

Application-level Endpoint:

```http
GET /api/v2/attachments/{attachmentId}/content
```

Backend Streaming을 사용한다. Storage 직접 URL이나 Presigned URL로 Redirect하지 않는다. 응답과 Document JSON에 Root·물리 경로·objectKey를 생성·주입하지 않고 안정적인 Application URL만 제공한다. Backend는 Block 내부 URL의 의미를 분석하지 않는다.

권한/Metadata를 짧은 read Transaction에서 확인한 뒤 Transaction 밖에서 stream을 전달한다. 전체 파일을 메모리에 올리지 않고 timeout·동시 stream 수·buffer를 제한하며 정상 종료/Client abort/실패 모두 stream을 닫는다. 삭제 claim 이후 content는 404다. no-store/nosniff와 안전한 Content-Type/Disposition/Length를 적용한다.

---

# 24. File Validation

Backend에서 최소 다음을 검증한다.

* Empty File
* Maximum Size
* MIME Type
* Extension
* Document 접근 권한
* Upload 권한

Frontend의 Validation 결과를 신뢰하지 않는다.

파일당 0 byte는 거부하고 최대 10 MiB, multipart 전체는 최대 11 MiB다. PNG/JPEG/PDF/UTF-8 TXT만 허용하고 선언 MIME·확장자·signature/UTF-8을 함께 검증한다. SVG/HTML/JS·실행 파일·archive는 거부한다. PNG/JPEG만 inline, PDF/TXT는 attachment download로 전달한다.

filename은 최대 255 UTF-16 units의 display metadata이며 path component/제어문자/CRLF를 제거·거부하고 안전하게 인코딩한다. Document title/content/attachmentIds의 한도와 strict parsing은 공통 계약 §40을 따른다.

---

# 25. Storage / DB Partial Failure

PostgreSQL과 LocalFileStorage는 하나의 Transaction이 아니다.

Upload는 권한/입력 검사 → Transaction 밖 파일 공개 → 짧은 Transaction의 부모·권한 재검증 및 TEMP Metadata commit 순서다. commit 이후에만 201을 반환한다.

- 파일 공개 후 DB rollback이 확정되면 보상 삭제한다. 보상 실패에는 가능한 별도 Transaction으로 삭제 작업을 남긴다.
- commit 응답 유실은 rollback으로 단정하지 않는다. 원래 Transaction 종료와 committed Metadata를 확인하기 전 파일을 삭제하지 않는다. DB 불가 또는 결과 불명확 시 안전하게 보류한다.
- 파일 삭제 실패는 영속 작업으로 재시도하고, 파일 삭제 후 DB 정리 실패도 같은 작업으로 복구한다. 부모 삭제가 rollback되면 Binary 제거를 실행하지 않는다.
- 파일 공개 직후 crash 또는 DB 불가와 보상 실패가 겹치면 DB 기록 없는 orphan이 생길 수 있다. Upload writer와 진행 중 파일/DB 작업의 종료를 확인하고 Root 내부의 오래된 파일을 Metadata/삭제 작업과 대조한 dry-run 후 미참조 파일만 회수한다. DB 조회나 writer 종료가 불명확하면 삭제하지 않는다.

Distributed Transaction 같은 과도한 구조는 도입하지 않는다.

---

# 26. TEMP Cleanup

조건:

```text
status = TEMP
AND
updatedAt < configured cutoff
```

처리:

```text
잠금 후 TEMP/updatedAt 재검증 및 영속 삭제 claim
   ↓
Transaction 밖 LocalFileStorage Object 삭제
   ↓
새 Transaction에서 Attachment DB Record 및 작업 삭제
```

기본 Grace Period 24시간, Spring Scheduling fixed delay 10분, batch 100개이며 `(updatedAt,id)` 순서다. cutoff와 같은 timestamp는 이번 대상에서 제외한다. 설정은 외부화하고 테스트에서는 자동 실행 대신 명시적으로 tick한다.

Save가 먼저 완료되면 Cleanup은 skip한다. 삭제 claim이 먼저 확정되면 연결을 409로 거부한다. due time/attempt/lease로 worker 중복과 재시작을 처리하며 실패한 삭제가 Attachment.updatedAt을 갱신하지 않는다. 정상 Root에서 파일 부재만 성공으로 간주하고 Root/mount/권한 오류는 재시도한다.

---

# 27. Attachment 삭제

TEMP:

```text
직접 삭제 가능
```

ATTACHED:

```text
직접 삭제 거부
```

예:

```text
409 ATTACHMENT_IN_USE
```

Document에서 Block 제거 → Save → TEMP 전환 이후 삭제한다.

TEMP 직접 삭제는 Grace Period 없이 영속 claim 경로를 사용한다. 파일 제거 및 Metadata 삭제 완료 후 200 `{deletedId}`를 반환한다. Storage 실패는 503과 영속 retry를 남기므로 실패 응답 후에도 정리가 진행될 수 있다. claim이 있는 TEMP의 재연결·중복 직접 삭제는 409 ATTACHMENT_DELETION_PENDING이며 권한 확인이 먼저다.

---

# 28. Document 삭제

Document 삭제 시 해당 Document의 Attachment 및 LocalFileStorage Object도 모두 정리해야 한다.

```text
Document
 ↓
Attachments
 ↓
LocalFileStorage Objects
```

DB FK Cascade만 적용하고 LocalFileStorage Binary를 남기지 않는다.

모든 TEMP/ATTACHED objectKey의 삭제 작업을 같은 DB Transaction에서 먼저 확보한 뒤 Attachment Metadata와 Document를 Hard Delete한다. Journal이 살아 있으면 Space는 유지한다. 작업 영속화 실패는 전체 rollback이며 Binary를 먼저 삭제하지 않는다.

**200 `{deletedId}`는 DB 삭제와 Binary 정리 작업 영속화의 성공**이다. 모든 파일의 즉시 물리 삭제를 뜻하지 않는다. commit 뒤 bounded 정리를 시도하고 장애·미완료 작업은 Scheduler가 재시도하며 재기동 후에도 복구한다. 작업 기록은 부모 FK cascade로 유실되거나 부모 삭제를 막지 않아야 한다. 기존 claim은 objectKey 기준으로 재사용한다. 상세 성공 응답과 Frontend 의미는 공통 계약 §31을 따른다.

---

# 29. Journal 삭제 Lifecycle

Journal이 자신의 DocumentSpace를 독점적으로 소유하므로 Journal 삭제 시 관련 Document 데이터도 Lifecycle 검토 대상이다.

개념:

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

기존 Journal의 ownership/revision 검사와 Hard Delete 정책을 유지한다. Binary 정리 작업을 확보한 뒤 Attachment → Document → Journal → DocumentSpace 순으로 같은 Transaction에서 제거한다.

기존 200 `{deletedId}`는 DB 삭제와 Binary 정리 작업 영속화를 의미한다. 파일 제거는 §28처럼 지연·재시도될 수 있다. counter 증가는 부모 명령당 한 번이며 반복 삭제 404와 역사적 생성 replay를 유지한다.

---

# 30. Domain 독립성

Document 시스템은 `journals`를 위해 만들어지지만 `journals`에 종속되도록 설계하지 않는다.

Document가 아는 것은:

```text
documentSpaceId
```

뿐이다.

향후:

```text
Project → DocumentSpace
Milestone → DocumentSpace
```

가 추가되더라도 Document Schema를 변경하지 않는 구조를 유지한다.

---

# 31. 권한

Journal Document 생성/목록은 기존 Journal 접근 권한 정책을 사용한다.

Document 수정/삭제/게시 및 Attachment 접근도 현재 인증/인가 체계를 기반으로 한다.

Session 인증, disabled User, CSRF/Origin, no-store와 타인/없는 리소스의 동일 404를 유지한다. archived Project 아래 기존 Journal의 Document 작성·편집·게시·삭제는 허용한다. authorId/createdBy는 인증 User의 Audit이며 소유권 판단에 사용하지 않는다.

PLAN의 DocumentSpaceAccess 경계와 Journal adapter로 상위 소유권을 해석한다. Document/Space에는 Journal import·SQL을 두지 않는다. 변경 잠금 순서는 workspace → 정렬된 Project → Journal → Space → 정렬된 Document → 정렬된 Attachment → 삭제 작업이며 discovery는 잠금 후 재검증한다. background Cleanup은 내부 maintenance 경로를 사용한다. 세부 Architecture/ADR 검토는 PLAN Session 1을 따른다.

Client가 다음을 임의로 결정하지 않는다.

```text
authorId
createdBy
documentSpaceId
status
objectKey
```

---

# 32. Environment Configuration

다음 환경 의존 정보는 코드에 하드코딩하지 않는다.

* PostgreSQL Host/Port
* FILE_STORAGE_ROOT
* Attachment/Document 크기 및 Streaming 제한
* TEMP Grace, Cleanup 주기·batch·enabled
* Spring Profile

환경변수 또는 기존 외부 설정 방식을 사용한다.

Local과 향후 Mini PC 환경에서 동일 Application Artifact를 사용할 수 있도록 한다.

Root는 필수 외부 설정이며 미리 준비한 전용 절대 경로·초기화 표식·실행 계정 권한을 검증한다. 누락된 mount/Root를 빈 저장소나 OS temp/working directory로 대체하지 않는다. Root/표식 접근 오류 중에는 Cleanup이 Metadata를 지우지 않는다.

기존 `.env` import와 DB 설정을 유지하고 신규 변수는 `.env` 및 안전한 `.env.example`에 함께 반영한다. Root 변경은 파일을 이동시키지 않는다. 환경 이동·복원 시 writer/cleanup을 중지하고 DB Metadata/삭제 작업과 파일 Root를 함께 보존·복원한다.

---

# 33. Local 개발 환경

1차 기준:

```text
Spring Boot
→ Local 실행

PostgreSQL
→ Local 또는 Docker Compose

LocalFileStorage
→ host의 전용 영속 디렉터리
```

현재 Repository 개발환경을 우선한다.

Application을 Docker로 실행하면 FILE_STORAGE_ROOT에 persistent named volume 또는 bind mount를 연결하고 non-root 실행 계정 권한을 검증한다. 컨테이너 임시 writable layer·tmpfs에 Binary를 영속 저장하지 않는다. 별도 Storage 서버는 추가하지 않는다.

두 mount 방식의 컨테이너 재생성 후 파일 보존·pending cleanup 재개와 동일 Artifact의 외부 Root 변경을 격리 환경에서 검증한다. 서로 다른 독립 Root를 사용하는 다중 Application 인스턴스와 Mini PC 운영 배포 자체는 1차 범위 밖이다.

---

# 34. 제외 범위

1차에서는 다음을 구현하지 않는다.

* Project Document 연동
* Milestone Document 연동
* Resource Document 연동
* 실시간 Collaboration
* Comment
* Mention
* Revision / History
* Template
* Share Link
* AI
* Custom Widget Backend
* GitHub Integration
* Server Integration
* Kubernetes Integration
* Elasticsearch / OpenSearch

향후 필요하다는 이유로 관련 Entity/Table/Service를 미리 만들지 않는다.

---

# 35. 향후 Domain 확장

새로운 Domain이 Document 기능을 사용할 경우:

```text
NewDomain
   ↓
DocumentSpace
   ↓
Document
```

방식으로 추가한다.

Document에:

```text
newDomainId
```

같은 필드를 추가하는 구조는 사용하지 않는다.

---

# 36. 구현 전 확인사항

PLAN-0015의 Repository Review를 기준으로 구현 전에 다음을 실제 코드에서 재확인한다.

* Spring Boot / Java 버전
* Package Architecture
* `journals` Entity
* `journals` Service
* `journals` Controller/API
* Journal 생성/삭제 Lifecycle
* Journal 권한 정책
* PostgreSQL / JSONB 기존 사례
* Migration Tool
* Audit 정책
* 인증/인가 방식
* Error Response Convention
* Pagination Convention
* Bean Validation
* OpenAPI / Swagger
* Transaction 구조
* File Upload 기존 구현
* Scheduler 구조
* Docker / Local 환경
* LocalFileStorage Root/권한/원자적 공개와 Docker 영속 mount 조건

---

# 37. Backend 완료 기준

다음이 정상 동작해야 한다.

1. Journal과 DocumentSpace 연결
2. 기존 Journal 데이터에 대한 DocumentSpace 전략 적용
3. 신규 Journal의 DocumentSpace Lifecycle 정상 동작
4. Journal Document 생성
5. Journal Document 목록 조회
6. 공용 Document 단건 조회
7. Document 수정
8. Document 삭제
9. BlockNote JSONB 저장/복원
10. DRAFT / PUBLISHED
11. Attachment Upload
12. LocalFileStorage Binary 저장
13. TEMP / ATTACHED 전이
14. Attachment Reconciliation
15. Stable Content Endpoint
16. TEMP Cleanup
17. ATTACHED 직접 삭제 방지
18. Document 삭제 시 Attachment/LocalFileStorage 정리
19. Journal 삭제 Lifecycle 처리
20. 권한 및 Validation
21. 환경 설정 외부화
22. 기존 API/OpenAPI Convention 준수

PLAN-0015의 세션 및 Final Validation으로 완료를 판단한다. 실제 파일 Root와 PostgreSQL을 사용한 Migration·보상·경쟁·재시작 복구, OpenAPI/Swagger 및 공통 계약 §39의 HTTP 흐름을 검증한다. 필수 검증을 skip한 경우 완료로 처리하지 않는다.


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
