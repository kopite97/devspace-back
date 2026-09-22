# Document Editor — Backend Requirements

## 1. 문서 목적

본 문서는 공용 Document Editor Backend 구현 PLAN을 작성하기 위한 요구사항 및 설계 지침이다.

본 문서 자체는 구현 PLAN이 아니다.

Backend 작업자는 현재 Spring Boot Architecture와 `journals` Domain의 Entity, Service, Controller, Persistence 및 권한 구조를 먼저 분석한 뒤 본 지침을 만족하는 PLAN을 작성해야 한다.

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
Journal #10
   ↓
DocumentSpace #100
```

Journal 쪽에서 `documentSpaceId`를 참조한다.

실제 Entity 관계 표현은 현재 프로젝트의 JPA/ORM Convention에 맞춘다.

---

# 7. 기존 Journal 데이터

이미 Journal 데이터가 존재할 수 있으므로 DocumentSpace 도입 전략을 PLAN에서 검토한다.

가능한 방식:

* Migration 과정에서 기존 Journal마다 DocumentSpace 생성
* 기존 Record는 최초 사용 시 Lazy 생성

현재 Migration 방식과 운영 데이터 구조를 분석하여 더 적절한 방법을 선택한다.

최종적으로 Document 기능을 사용하는 Journal에는 유효한 DocumentSpace가 존재해야 한다.

---

# 8. Journal 생성

향후 새로운 Journal이 생성될 때 해당 Journal의 DocumentSpace도 정상적으로 준비되어야 한다.

구체적인 생성 시점은 기존 Journal 생성 Flow를 분석한 후 PLAN에서 확정한다.

DocumentSpace 생성 실패로 Journal/Document 관계가 불완전하게 남지 않도록 Transaction 경계를 검토한다.

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

프로젝트에서 사용하는 JSONB Mapping 방식에 맞는 타입을 사용한다.

예:

```java
JsonNode
```

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

---

# 12. Document 생성

Document 생성은 Journal Context에서 수행한다.

개념 API:

```http
POST /api/journals/{journalId}/documents
```

실제 URI는 기존 `journals` API Convention을 먼저 확인하여 확정한다.

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

맥락 없는 다음 API를 기본 사용자 생성 진입점으로 사용하지 않는다.

```http
POST /api/documents
```

---

# 13. Document 목록

Document 목록 역시 Journal Context에서 제공한다.

개념:

```http
GET /api/journals/{journalId}/documents
```

DocumentSpace ID를 Frontend가 직접 Query하지 않는다.

Backend가 Journal에서 Space를 Resolve한다.

---

# 14. Document 단건 API

Document 생성 이후에는 전역적으로 유일한 `documentId`를 기준으로 공용 API를 사용할 수 있다.

개념:

```http
GET    /api/documents/{documentId}
PUT    /api/documents/{documentId}
DELETE /api/documents/{documentId}

POST   /api/documents/{documentId}/publish
```

실제 URL Convention은 기존 프로젝트 규칙을 따른다.

---

# 15. 전역 Document 목록

1차 구현에서는 다음과 같은 전역 목록 API를 필수로 만들지 않는다.

```http
GET /api/documents
```

현재 사용자 목록 진입점은 `journals`이다.

향후 Admin 또는 Search 요구사항이 생길 경우 별도로 검토한다.

---

# 16. DocumentSpace 관리 API

Frontend가 DocumentSpace를 직접 관리하지 않으므로 다음 Public CRUD는 1차에서 제공하지 않는다.

```text
POST   /api/document-spaces
PUT    /api/document-spaces/{id}
DELETE /api/document-spaces/{id}
GET    /api/document-spaces
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

DB 변경은 가능한 하나의 Transaction 내에서 처리한다.

MinIO Object를 상태 변경 때마다 이동시키지 않는다.

---

# 21. Storage Abstraction

MinIO SDK 사용을 Application 전체에 직접 분산시키지 않는다.

개념:

```java
interface FileStorage {
    StoredObject upload(...);
    Resource get(...);
    void delete(...);
}
```

1차 구현:

```text
MinioFileStorage
```

향후 다른 Storage를 사용할 수 있도록 Interface 수준에서만 확장성을 유지한다.

현재 사용하지 않는 구현체를 미리 만들지 않는다.

---

# 22. MinIO

MinIO에는 Binary File만 저장한다.

PostgreSQL:

```text
Document JSON
Attachment Metadata
Object Key
```

MinIO:

```text
Actual Binary
```

권장 Object Key:

```text
documents/{documentId}/{uuid}.{ext}
```

Object Key 생성은 Backend 책임이다.

---

# 23. Stable Content URL

Frontend는 MinIO 주소를 알지 않는다.

Application-level Endpoint:

```http
GET /api/attachments/{attachmentId}/content
```

내부 방식은 다음 중 하나를 사용할 수 있다.

* Backend Streaming
* Temporary Presigned URL Redirect

만료되는 Presigned URL을 Document JSON에 영구 저장하지 않는다.

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

SVG 등의 Active Content는 보안 검토 없이 허용하지 않는다.

---

# 25. Storage / DB Partial Failure

PostgreSQL과 MinIO는 하나의 Transaction이 아니다.

예:

```text
MinIO Upload 성공
DB Insert 실패
```

시 업로드된 Object를 정리하는 보상 로직을 적용한다.

반대로 Delete 과정의 Partial Failure도 추적 가능해야 한다.

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
MinIO Object 삭제
   ↓
Attachment DB Record 삭제
```

Cleanup Schedule과 Grace Period는 현재 Scheduler 구조를 분석한 후 PLAN에서 결정한다.

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

---

# 28. Document 삭제

Document 삭제 시 해당 Document의 Attachment 및 MinIO Object도 모두 정리해야 한다.

```text
Document
 ↓
Attachments
 ↓
MinIO Objects
```

DB FK Cascade만 적용하고 MinIO Binary를 남기지 않는다.

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
MinIO Objects
```

기존 `journals`가 Soft Delete인지 Hard Delete인지 실제 코드에서 확인하고 그 정책에 맞춰 PLAN을 작성한다.

임의로 삭제 방식을 변경하지 않는다.

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
* MinIO Endpoint
* MinIO Credential
* MinIO Bucket
* Spring Profile

환경변수 또는 기존 외부 설정 방식을 사용한다.

Local과 향후 Mini PC 환경에서 동일 Application Artifact를 사용할 수 있도록 한다.

---

# 33. Local 개발 환경

1차 기준:

```text
Spring Boot
→ Local 실행

PostgreSQL
→ Local 또는 Docker Compose

MinIO
→ Docker Compose
```

현재 Repository 개발환경을 우선한다.

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

# 36. PLAN 작성 전 확인사항

Backend PLAN 작성 전에 다음을 실제 코드에서 확인한다.

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
* MinIO 기존 설정 여부

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
12. MinIO Binary 저장
13. TEMP / ATTACHED 전이
14. Attachment Reconciliation
15. Stable Content Endpoint
16. TEMP Cleanup
17. ATTACHED 직접 삭제 방지
18. Document 삭제 시 Attachment/MinIO 정리
19. Journal 삭제 Lifecycle 처리
20. 권한 및 Validation
21. 환경 설정 외부화
22. 기존 API/OpenAPI Convention 준수

이 요구사항을 기준으로 Backend 구현 PLAN을 작성한다.
