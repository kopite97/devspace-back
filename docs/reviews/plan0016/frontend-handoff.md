# Document 첨부 Registry 복원

PLAN-0016은 기존 9개 API의 응답을 확장한다. 별도 첨부 목록 API는 추가하지 않는다.

## 응답과 복원

문서 생성·상세·저장·게시 응답에 필수 `attachments` 배열이 추가되었다. 새로고침 시 `GET /api/v2/documents/{documentId}` 응답만으로 저장된 Registry를 구성한다.

```ts
type SavedAttachment = {
  id: string;                 // UUID
  documentId: string;         // 현재 문서 UUID
  originalFilename: string;
  contentType: string;
  size: number;               // bytes
  status: 'ATTACHED';
  contentUrl: string;         // /api/v2/attachments/{id}/content
  createdAt: string;          // UTC ISO-8601
};

const registry = new Map(document.attachments.map(file => [file.id, file]));
```

- 배열과 모든 항목 필드는 필수·non-null이다. 빈 문서는 `[]`이며 저장된 최대 100개가 모두 반환된다.
- `attachmentIds`와 `attachments.map(file => file.id)`는 동일한 UUID 순서다. 본문 배치는 `content` 순서를 따른다.
- Upload 응답은 기존 `TEMP`다. PUT의 attachmentIds에 포함해 저장하면 `ATTACHED`가 된다. 미저장·연결 해제·삭제 진행 TEMP는 복원 배열에 없다.
- PUT 요청은 `{title,content,attachmentIds}`만 보낸다. GET 응답 전체를 PUT하면 응답 전용 필드 때문에 400이 발생한다.
- 복원 실패를 빈 Registry로 대체해 저장하지 않는다. 편집 상태를 유지하고 저장을 중단한 뒤 재조회한다.
- 본문에만 남은 ID/URL은 서버가 자동 수정하지 않는다. 해당 참조를 사용할 수 없다는 UI 처리는 프론트가 담당한다.
- 복원은 Metadata 조회다. 이후 파일이 삭제되면 content 요청은 404, 저장소 장애는 503일 수 있다. 파일 접근에는 기존 세션 인증이 필요하다.

## 오류와 삭제

| HTTP / code | 처리 기준 |
| --- | --- |
| 400 VALIDATION_ERROR | 잘못된 UUID/중복/null/101개 이상, 빈 파일·파일 형식 오류 |
| 404 RESOURCE_NOT_FOUND | 없음·접근 불가; PUT에서 다른 문서 첨부 ID도 동일 |
| 409 ATTACHMENT_IN_USE | 연결된 첨부 직접 삭제 불가. 먼저 PUT에서 연결 해제 |
| 409 ATTACHMENT_DELETION_PENDING | 삭제 claim이 확정된 첨부 연결/중복 삭제 |
| 413 PAYLOAD_TOO_LARGE | 본문·JSON 요청·파일·Multipart 크기 초과 |
| 415 UNSUPPORTED_MEDIA_TYPE | 요청 자체의 Content-Type 오류 |
| 503 STORAGE_UNAVAILABLE | 저장소 장애/스트리밍 용량 제한 |
| 500 INTERNAL_ERROR | 복원 불변식 위반 등 내부 오류. 빈 결과로 해석하지 않음 |

PUT은 모든 첨부의 존재·소속을 먼저 검사한다. 없는/다른 문서 첨부와 삭제 진행 첨부를 함께 보내면 순서와 무관하게 404다. 요청 실패 시 기존 문서와 첨부 관계는 유지된다.

Document/Attachment DELETE 성공 응답은 필수 UUID `deletedId`다. `deletedIt`은 없다. Document 삭제 200은 Metadata 삭제와 영속 파일 정리 예약을 뜻하며, TEMP 직접 삭제 200은 파일과 Metadata 제거 완료를 뜻한다. 삭제 503 이후에도 서버 재시도는 진행될 수 있다.

## 기본 크기 제한

| 대상 | 한도(이하 허용) |
| --- | --- |
| content | 파싱 후 UTF-8 직렬화 1,048,576 bytes |
| 문서 JSON 요청 전체 | 1,114,112 bytes |
| 파일 | 10,485,760 bytes, 빈 파일 거부 |
| Multipart 전체 | 11,534,336 bytes, 경계·헤더 포함 |

설정으로 byte 제한을 바꿀 수 있다. 문자열 길이는 UTF-8 byte 크기와 다르다. nesting depth는 100이며 초과는 400이다. 허용 파일은 PNG/JPEG/PDF/UTF-8 TXT다.

기존 저장 직렬화, 저장 성공 후 게시, 생성·Upload의 자동 재시도 중복 위험, 세션/CSRF/Origin 정책은 유지된다.
