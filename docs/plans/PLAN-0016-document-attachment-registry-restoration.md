# PLAN-0016: Document Attachment Registry Restoration

Status: `completed`
Date: 2026-09-23

## Goal

Frontend가 새로고침 후 Document 상세 응답 하나로 저장된 첨부 최대 100개의 Attachment Registry를 누락 없이 복원할 수 있게 한다. 첨부 접근·상태·오류, 요청 크기 제한, 삭제 응답의 필수 필드를 실제 구현·공통 계약·OpenAPI에서 일치시킨다.

이 문서는 [Runtime Template](PLAN-TEMPLATE-RUNTIME.md)을 따른다. 작성 요청은 실행 승인이 아니다. 승인 전에는 구현과 기존 계약을 변경하지 않는다. 승인 후 이 PLAN의 명시적 변경 사항이 [PLAN-0015](PLAN-0015-document-editor-phase-one.md)의 해당 계약을 보완하며, 나머지 동작은 유지한다.

## Scope

- 기존 Document 상세 DTO에 필수 `attachments` 배열 추가. 같은 DTO를 반환하는 생성·상세·저장·게시 응답 모두에 적용한다.
- 세션 소유권과 Document 소속을 검증한 메타데이터 조회, ID/메타데이터 일관성, 최대 100개 복원 및 동시성 검증.
- PUT의 없는/다른 Document 첨부 오류를 기존 PLAN의 404 계약과 일치시키고 테스트를 수정한다.
- 크기 제한과 첨부 오류를 계약/OpenAPI에 구체화하고 Document·Attachment 삭제 응답의 `deletedId`를 필수 UUID로 선언한다.
- Backend Requirements와 Common Contract를 승인된 변경에 맞춰 갱신한다.
- 새 endpoint, DB Migration, 신규 의존성·인프라·아키텍처 패턴은 추가하지 않는다. 기존 Repository와 Application 경계를 사용한다.
- Frontend 구현, 미저장 TEMP 복구, 본문 JSON의 첨부 추론·수정, 파일 복구, 운영 배포·실제 DB 초기화는 제외한다. 기존 Journal 본문 및 API는 유지한다.

## Repository Review

- 확인 지침: 루트 AGENTS, Plan Guide, Architecture, API, Database, Flyway, Security, Spring Boot 및 Implementation/Application. 구현 시 변경 대상에 필요한 세부 지침을 추가 확인한다.
- `DocumentSnapshot`은 `attachmentIds`만 제공하고 `DocumentQueryService.get`은 REPEATABLE_READ에서 소유권 확인 후 ID를 조회한다.
- `AttachmentSnapshot`에 필요한 8개 필드가 이미 있지만 업로드 응답에만 사용한다. 메타데이터 조회 API는 없다.
- `AttachmentRepositoryAdapter.attachedIds`는 해당 Document의 ATTACHED 전체를 UUID 순서로 조회한다. 저장 서비스가 100개 상한을 강제한다.
- `DocumentCommandService.update`는 없는/타 Document 첨부를 현재 400으로 거부한다. PLAN-0015/Common Contract는 404를 명시하고 기존 `DocumentMutationTests`는 400을 기대하므로 모두 정합화해야 한다.
- `DocumentLimits`는 content 기본 1 MiB, JSON 요청 전체는 content 한도 + 64 KiB를 적용한다. Servlet은 파일 10 MiB, Multipart 11 MiB 기본 제한을 적용한다.
- 삭제 DTO는 런타임에서 `deletedId`를 채우지만 `DocumentOpenApiConfiguration`의 필수 필드 지정 대상에 포함되어 있지 않다. 현재 생성 명세를 먼저 확인한다.
- 신규 장기 아키텍처 결정이나 반복 구현 규칙을 도입하지 않으므로 별도 ADR/Agent Guide 변경은 범위에 포함하지 않는다.

## Proposed Contract

### Document 응답 확장

다음 응답에 기존 필드를 보존하면서 `attachments`를 추가한다.

| Method / Path | 성공 응답 |
| --- | --- |
| POST `/api/v2/journals/{journalId}/documents` | 201, `attachmentIds: []`, `attachments: []` |
| GET `/api/v2/documents/{documentId}` | 200, 저장된 Document와 첨부 메타데이터 |
| PUT `/api/v2/documents/{documentId}` | 200, 저장/첨부 관계 변경이 반영된 응답 |
| POST `/api/v2/documents/{documentId}/publish` | 200, 현재 저장된 첨부를 포함한 게시 응답 |

- `attachments`는 항상 존재하는 non-null 배열이며 빈 경우 `[]`, 최대 100개다. 각 항목은 `id`, `documentId`, `originalFilename`, `contentType`, `size`, `status`, `contentUrl`, `createdAt`을 필수 non-null로 제공한다.
- ID는 UUID 문자열, size는 byte 단위 정수, createdAt은 UTC ISO-8601, contentUrl은 `/api/v2/attachments/{id}/content` 형식이다. Storage Root/objectKey/절대 경로는 노출하지 않는다.
- Registry 복원 대상은 해당 Document의 저장된 ATTACHED 항목이다. 따라서 이 배열의 status는 ATTACHED다. 기존 업로드 응답의 TEMP 의미는 유지한다.
- OpenAPI에서 업로드 항목과 저장된 첨부 항목의 status 제약을 구분한다. 공용 `AttachmentSnapshot` 스키마를 ATTACHED로 좁혀 업로드 TEMP 응답을 잘못 표현하지 않는다. 필요한 응답 DTO/스키마 분리는 기존 계층 내에서 처리한다.
- `attachmentIds`와 `attachments.map(id)`는 동일한 집합과 UUID 정렬 순서를 갖는다. 하나의 메타데이터 조회 결과로 두 필드를 구성하여 별도 조회 사이의 불일치를 막는다. 본문 배치 순서는 content가 결정한다.
- GET은 소유권·Document·첨부를 동일 REPEATABLE_READ snapshot에서 조회한다. 저장 응답은 해당 명령의 Transaction 내 상태를 사용하며 기존 잠금 순서와 commit 후 성공 응답 정책을 유지한다.
- 문서별 조건으로 일괄 조회하며 항목별 DB 조회 또는 파일 존재 검사로 N+1을 만들지 않는다. 이 컬렉션은 저장 상한 100개로 제한되므로 페이지 분할하지 않는다. `limit(100)`으로 초과 데이터를 조용히 잘라 정상 응답하지 않는다.
- 100개는 TEMP를 포함한 전체 Upload 수의 상한이 아니라 저장된 ATTACHED 관계의 상한이다. 조회는 ATTACHED로 제한하고, 비정상 데이터에서도 메모리를 제한하도록 최대 101개를 읽어 초과 여부를 판단할 수 있다. 삭제 claim 검사도 문서별 일괄 조회/exists로 처리하며 기존 `pending(id)`를 항목마다 호출하지 않는다.
- Journal의 Document 목록 요약은 확장하지 않는다. PUT 요청은 기존 `{title,content,attachmentIds}`이며 `attachments`는 응답 전용이다.

### 권한·누락·삭제·경합 규칙

| 상태/상황 | 동작 |
| --- | --- |
| 비인증/비활성 계정 | 기존 401/403 보안 계약 적용 |
| 없는/타인 Document | 404 RESOURCE_NOT_FOUND; 첨부 메타데이터를 먼저 읽어 노출하지 않음 |
| 정상 ATTACHED | 해당 Document 소속 항목을 전부 반환 |
| TEMP, 연결 해제된 첨부 | 복원 배열에서 제외. 미저장 Upload 복구는 제공하지 않음 |
| 삭제 claim이 있는 TEMP | 복원 배열에서 제외; 파일 조회 404, 연결/중복 직접 삭제 409 |
| 삭제 완료 Metadata | 복원 배열에 없음; 개별 파일 조회/직접 삭제는 404 |
| 본문에만 남은 ID/URL | content를 자동 수정하거나 Registry 항목을 추론하지 않음. Frontend는 본문 참조와 복원 목록 차이를 처리 |
| Metadata는 ATTACHED지만 물리 파일 유실 | 메타데이터는 반환. 정상 Root에서 파일 부재 시 content API는 404, Root/저장소 장애는 503 |
| 조회 중 detach/삭제 | snapshot 시점 결과를 반환할 수 있음. 후속 파일 요청의 404/503 처리를 계약에 명시 |

정상 삭제 lifecycle은 ATTACHED에 삭제 claim을 만들지 않는다. ATTACHED+claim 또는 100개 초과 등 불변식 위반은 항목을 조용히 누락시켜 성공시키거나 조회에서 자동 복구하지 않고 내부 오류로 처리·기록한다. 일반 조회가 완전한 스냅샷을 반환한다는 보장을 유지한다.

부모 삭제는 같은 Transaction에서 claim 생성과 Metadata 삭제를 수행하므로 정상적인 동시 조회가 중간 ATTACHED+claim 상태를 관측해서는 안 된다. 비정상 상태는 `500 INTERNAL_ERROR`로 처리하고 내부 로그에 requestId와 진단에 필요한 식별자만 남긴다. Metadata 복원 실패를 빈 배열로 대체하여 저장하면 기존 첨부가 해제될 수 있으므로 Frontend 전달 계약에는 조회 실패 시 편집 상태를 유지하고 저장을 중단하도록 명시한다.

### 오류·제한·삭제 응답

| HTTP / code | 적용 |
| --- | --- |
| 400 VALIDATION_ERROR | 잘못된 UUID/타입, ID 중복/null/101개 이상, 파일 형식·확장자·MIME·signature·UTF-8 검증 실패, 빈 파일 |
| 404 RESOURCE_NOT_FOUND | 대상 없음/권한 없음, PUT의 없는·삭제 완료·타 Document 소속 첨부. 같은 사용자 소유의 다른 Document도 동일 처리 |
| 409 ATTACHMENT_IN_USE | ATTACHED 직접 삭제 |
| 409 ATTACHMENT_DELETION_PENDING | 해당 Document의 삭제 claim 첨부 연결/중복 직접 삭제 |
| 413 PAYLOAD_TOO_LARGE | content/전체 JSON/파일/Multipart 한도 초과 |
| 415 UNSUPPORTED_MEDIA_TYPE | HTTP 요청 자체의 미지원 Content-Type; 파일 allowlist 위반은 400 |
| 503 STORAGE_UNAVAILABLE | Upload/content/TEMP 삭제 Storage 실패 및 기존 stream 용량 제한 |

- 소유권·소속 검증이 상태 conflict보다 우선한다. 타인/타 Document 첨부의 삭제 진행 여부를 노출하지 않는다. 실패 PUT은 Document/첨부 상태/counter를 모두 유지한다.
- 유효한 형식의 ID 목록 전체에 대해 존재·Document 소속 검증을 완료한 뒤 삭제 claim 충돌을 판정한다. 같은 요청에 소유한 삭제 진행 첨부와 없는/타 Document 첨부가 섞이면 입력 순서·UUID 정렬과 무관하게 404다. UUID 형식/중복/개수 등 요청 구조 오류는 기존 400 검증을 유지한다.
- 오류 공통 형식 `{code,message,fieldErrors,requestId}`와 기존 인증/CSRF/Origin/내부 오류를 유지한다. 전송 시작 이후 stream 실패는 JSON 응답 전환 없이 종료한다.
- 기본 content 한도 1,048,576 bytes, 전체 문서 JSON 요청 1,114,112 bytes(content 한도 + 65,536), 파일 10,485,760 bytes, Multipart 전체 11,534,336 bytes를 명시한다. content 한도는 UTF-8 직렬화 기준이며 nesting depth 100을 유지한다.
- byte 한도는 OpenAPI 문자열 `maxLength`로 대체하지 않는다. content는 파싱 후 서버 직렬화 크기, 전체 JSON/Multipart는 요청 byte 크기라는 측정 기준과 허용 경계(한도 이하)를 설명한다. nesting depth/파일 형식 오류는 400이며 byte 한도 초과의 413과 구분한다.
- 기존 `DOCUMENT_CONTENT_MAX_BYTES`, `ATTACHMENT_MAX_BYTES`, `ATTACHMENT_REQUEST_MAX_BYTES` 설정을 유지한다. 런타임 설정과 고정 기본값을 구분해 문서화하고 새 설정을 추가하지 않는다.
- Document·Attachment 삭제 성공 응답은 200 `{deletedId}`이며 `deletedId`를 required non-null UUID로 선언한다. `deletedIt` 필드는 없다.
- Document 삭제 성공은 Metadata 삭제 및 Binary 정리 작업 영속화, TEMP 직접 삭제 성공은 Binary/Metadata 제거 완료를 의미한다. 실패 뒤에도 영속 정리 재시도가 진행될 수 있다는 기존 계약을 유지한다.

## Execution Sessions

세션은 순서대로 진행한다. 해당 구현과 검증을 마친 뒤 다음 세션으로 이동하며 실제 완료한 항목만 체크한다.

### Session 1: 계약 확정 및 명세 기준선

Objective: 응답 확장과 오류 변경을 Frontend가 사용할 수 있는 명시적 계약으로 고정한다.

Implementation:

- [x] 사용자 승인 후 상태를 active로 변경한다.
- [x] 실제 OpenAPI의 Document/Attachment/삭제 스키마 및 기존 테스트를 확인하고 위 계약과 차이를 기록한다.
- [x] Backend Requirements와 Common Contract에 응답 8개 필드, 100개 복원, 상태·경합·파일 유실 규칙, 전체 JSON 한도, 오류 및 삭제 필수 필드를 반영한다.
- [x] PLAN-0015에 승인된 후속 PLAN 참조를 추가해 과거 완료 범위와 현재 확장 계약을 구분한다.

Validation:

- [x] ID/메타데이터 일치, 응답 전용 필드, POST/GET/PUT/Publish 공통 응답과 목록 요약 범위가 명확하다.
- [x] 400→404 변경과 additive 응답 필드가 Frontend에 미치는 영향을 기록하고 예시가 계약과 일치한다.
- [x] 업로드 TEMP/복원 ATTACHED 스키마와 실패 시 Registry를 빈 값으로 저장하지 않는 Frontend 전달 규칙을 확인한다.

### Session 2: 일괄 메타데이터 복원

Objective: 권한 검증을 거친 일관된 Document 응답으로 Registry를 복원한다.

Implementation:

- [x] 기존 Repository를 통해 해당 Document의 ATTACHED 메타데이터를 일괄 조회하고 응답 DTO로 변환한다.
- [x] 공통 Document 응답에 attachments를 추가하고 같은 결과로 attachmentIds를 구성한다.
- [x] 소유권·소속·snapshot 및 명령의 잠금/Transaction 경계를 유지하고 Storage I/O 없이 조회한다.
- [x] 불변식 위반 시 성공 응답의 조용한 누락을 방지한다.

Validation:

- [x] 빈 문서, 저장된 첨부 1개/정확히 100개를 새 GET으로 조회해 8개 필드 값과 ID의 개수·집합·정렬을 검증한다.
- [x] POST/PUT/최초 및 반복 Publish 응답에서도 동일 스키마와 첨부 관계를 검증한다.
- [x] TEMP/detach/삭제 claim/삭제 완료 항목이 제외되고 타 Document·타 사용자 정보가 유출되지 않는다.
- [x] ATTACHED 100개와 추가 TEMP/삭제 진행 TEMP가 함께 존재해도 저장된 100개가 모두 복원된다.
- [x] 격리 DB에 ATTACHED 101개 또는 ATTACHED+claim을 의도적으로 구성하면 500을 반환하고 조용한 잘림·부분 응답·자동 데이터 수정이 없다.
- [x] 세션 미인증·타 사용자·archived Project의 기존 접근 정책을 검증한다.
- [x] 실제 PostgreSQL에서 조회와 detach/부모 삭제 경합 시 ID/메타데이터가 혼합되지 않음을 검증한다.
- [x] 경합 검증은 sleep 타이밍에 의존하지 않고 Transaction 경계를 제어한다. 부모 삭제 전 snapshot의 완전한 200 또는 삭제 후 404를 허용하되 중간 상태로 인한 500이나 부분 응답은 허용하지 않는다.
- [x] 파일 유실·Root 장애에서도 Metadata 조회는 성공하고 후속 content API가 각각 404/503을 반환한다.
- [x] 첨부 1개와 100개의 쿼리 관측으로 N+1이 없고, 읽기가 counter/updatedAt/content를 변경하지 않음을 확인한다.

### Session 3: 오류 및 OpenAPI 정합화

Objective: 실제 오류·제한·필수 필드가 생성 클라이언트에 정확히 전달되게 한다.

Implementation:

- [x] PUT의 없는/타 Document 첨부를 404로 변경하고 관련 기존 테스트 기대값을 수정한다.
- [x] attachments의 required/non-null/maxItems=100, 항목 8개 필드의 required/non-null 및 ID의 UUID 형식, 삭제 DTO deletedId의 required/non-null/UUID를 생성 명세에 반영한다. 두 응답 배열의 ID 동등성은 설명과 테스트로 보장한다.
- [x] endpoint별 정확한 오류 코드, 파일 allowlist, 설정 가능한 byte 제한 및 성공 의미를 OpenAPI에 반영한다.

Validation:

- [x] 잘못된 UUID·중복·101개는 400, 없는/타 Document·타인 첨부는 404, 소유한 삭제 진행 첨부는 409이며 PUT 전체 rollback을 검증한다.
- [x] 소유한 삭제 진행 첨부와 없는/타 Document 첨부의 혼합 요청을 순서와 UUID 크기를 바꾸어 검증해 항상 404이고 상태/counter가 유지됨을 확인한다.
- [x] 출력 attachments를 POST/PUT 요청에 보내면 기존 strict parsing 정책대로 400이다. 응답 스키마의 필수 추가가 요청 필드 확장으로 오인되지 않는다.
- [x] 삭제 성공 JSON에 deletedId가 항상 존재하고 UUID 값이 일치한다. 생성 OpenAPI에서도 required이며 deletedIt은 없다.
- [x] content 직렬화 한도와 전체 JSON 한도를 독립적으로 경계값/초과값으로 검증한다. 다바이트 UTF-8과 JSON 공백을 포함해 두 제한을 혼동하지 않는다.
- [x] 실제 HTTP Multipart에서 허용 최대 파일/초과 파일, 전체 요청 초과, 빈 파일/잘못된 타입과 공통 오류 형식을 검증한다. Multipart 전체 한도 검증은 파일별 한도와 구분한다.
- [x] Multipart 전체 한도 검증은 격리 실행의 request 한도를 파일 한도보다 작게 설정해 정상 단일 파일/허용 part 구조로 수행한다. 경계와 overhead를 포함한 실제 byte를 기록하고 운영 설정은 변경하지 않는다.
- [x] 정상 생성/저장/게시·삭제, Upload/stream/cleanup 및 기존 제한 관련 회귀 테스트를 통과한다.

### Session 4: Frontend 복원 흐름 및 최종 검증

Objective: 업로드 응답을 잃어도 저장된 Registry를 복구할 수 있음을 재현한다.

Implementation:

- [x] 격리 DB/전용 Storage에서 문서 생성 → Upload → attachmentIds 저장 → 메모리 Registry 폐기/페이지 새로고침 → GET만으로 Registry 재구성 → content 조회 → detach/삭제 흐름을 실제 HTTP로 검증한다.
- [x] 기존 브라우저 검증 도구의 최소 harness로 수행하며 제품 Frontend 화면이나 의존성을 추가하지 않는다. Upload 응답을 보관하지 않은 새 실행 컨텍스트에서 8개 필드를 복원해 우연히 메모리 캐시를 재사용하는 검증을 방지한다.
- [x] 100개 저장 후 복원 결과의 누락/중복 여부와 문서·첨부 삭제 후 상태를 검증 기록에 남긴다.
- [x] Frontend 전달 문서에 필드별 의미, 상태 제외 규칙, snapshot 이후 파일 조회 실패 처리와 미저장 TEMP 제외를 정리한다.

Validation:

- [x] Swagger UI에서 기존 9개 API와 확장 스키마/삭제 응답을 확인한다.
- [x] 검증 결과를 `docs/reviews/plan0016/`에 기록하되 개인 ID/본문/파일/인증정보는 게시하지 않는다.

## Final Validation

- [x] Java 21에서 `./gradlew.bat test check bootJar --no-daemon` 성공.
- [x] 격리 환경의 실제 HTTP/브라우저 Registry 복원 검증 성공.
- [x] 기존 Migration/실제 개발·운영 DB 데이터/Agent Guide에 변경이 없다.
- [x] 이 PLAN으로 발생한 검증 실패가 남아 있지 않다.
- [x] 모든 Execution Session이 완료되었다.

### API Validation

- [x] 변경된 응답이 `/v3/api-docs`에 반영되었다.
- [x] 요청/응답 스키마가 승인된 계약과 일치한다.
- [x] Bean Validation 및 배열/필수 필드 제약이 적절히 반영되었다.
- [x] HTTP method/status 및 정확한 오류 코드가 계약과 일치한다.
- [x] Swagger UI가 변경된 계약을 정확히 렌더링한다.

## Completion

모든 필수 세션과 적용 가능한 검증을 통과하고 미해결 blocker가 없을 때만 completed로 변경한다. 그때 `docs/plans/README.md`의 완료 목록에 순서대로 추가한다. 검증이 불가능하면 미완료 항목과 원인·대체 증거를 남기며 성공으로 표시하지 않는다.

## Plan Review

2026-09-23 재검토: 기존 삭제 claim 조회가 ID별이라는 점, PUT 검증이 항목별 순서로 404/409를 결정할 수 있다는 점, 업로드와 상세 응답이 공유할 수 있는 status 스키마, TEMP 개수와 ATTACHED 상한의 차이를 확인했다. 일괄 claim 검사, 목록 전체 소속 검사 우선, 상태별 응답 스키마, 최대 101개 탐지와 관련 검증을 보완했다. Multipart 경계 검증과 브라우저 harness의 실행 범위도 구체화했다. 이번 변경은 PLAN 문서만 수정하며 상태는 proposed로 유지한다.


## Completion Evidence

2026-09-23: all sessions completed. Full suite: 326 tests, zero failures/errors/skips, test check bootJar passed. Final registry assertions passed separately without production code changes. Actual Chrome reload restored all 100 attachments; file and Multipart boundary checks passed. See [implementation validation](../reviews/plan0016/implementation-validation.md) and [frontend handoff](../reviews/plan0016/frontend-handoff.md).
