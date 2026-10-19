# GitHub App·Issue–Task 프론트엔드 구현 안내

작성일: 2026-10-19. 백엔드 구현 및 자동 검증 후 작성한 실제 계약이다.
전체 빌드 365개 테스트와 후속 집중 테스트 56개가 통과했다.
검증 근거는 [구현 검증 기록](../../reviews/plan0017/implementation-validation.md),
운영 설정은 [GitHub App 설정](../../operations/github-app-setup.md)을 참고한다.
스키마 원본은 실행 서버의 `/v3/api-docs`, 확인 화면은 `/swagger-ui/index.html`이다.
예제의 ID·이름·원문은 모두 설명용 가상 데이터다.

## 1. 화면 범위와 기본 원칙

- 서비스 사용자당 GitHub 계정 하나, 프로젝트당 저장소 여러 개를 연결한다.
  같은 프로젝트에 같은 저장소를 중복 연결할 수 없다. 활성/해제 중 연결 상한은 100개다.
- GitHub Issue를 선택해 **새 Task**로 가져온다. 기존 일반 Task를 연결하는 화면/API는 없다.
- GitHub는 제목·본문·상태의 원본이다. 로컬에서는 priority/tag만 편집한다.
- 개발일지·문서·마일스톤 연동, PR/Actions, GitHub 쓰기, 새 위젯, SSE는 이번 범위가 아니다.
- 계정 연결, 저장소 연결, 소스 수집, Task 반영, 접근 검증은 서로 다른 상태다.
  저장소 연결 직후 모든 Issue와 Task가 준비됐다고 표시하지 않는다.

## 2. 세션, CSRF, 이동과 캐시

모든 브라우저 API는 기존 서비스 로그인 세션을 사용한다. 요청에 세션 쿠키를 포함한다.
`GET /api/v1/auth/csrf`의 `csrfToken`을 모든 POST/PUT/PATCH/DELETE의 `X-CSRF-Token`
헤더에 보낸다. 미리보기 POST도 포함된다. 기존 backend Origin 정책을 그대로 따른다.
브라우저 출처는 backend의 `APP_ORIGIN` 설정과 일치해야 한다.

```js
const csrf = await fetch('/api/v1/auth/csrf', { credentials: 'include' })
  .then(r => r.json());
const response = await fetch(`${repositoryBase}/task-imports`, {
  method: 'POST', credentials: 'include',
  headers: {
    'Content-Type': 'application/json',
    'X-CSRF-Token': csrf.csrfToken,
    'Idempotency-Key': crypto.randomUUID()
  },
  body: JSON.stringify({ revision: settings.revision, issueIds: selectedIssueIds })
});
// 202는 접수다. 응답의 jobId로 완료/부분 실패를 확인한다.
```

응답은 `Cache-Control: no-store`다. GitHub token, PEM, Client secret, Webhook secret,
암호화 키를 프론트 환경변수·브라우저 저장소·로그에 넣지 않는다.

GitHub 연결 시작 API의 `authorizationUrl`로 브라우저를 이동한다. callback은 backend가 처리하고
설정된 `GITHUB_FRONTEND_RETURN_URL?github=...`로 303 이동한다.
`github` 값은 `connected`, `denied`, `account_conflict`, `failed`다.
복귀 쿼리만 신뢰하지 말고 계정 API를 다시 조회한다. state/code를 프론트가 재사용하지 않는다.
동의 시작과 callback은 같은 backend 호스트의 같은 서비스 세션이어야 한다.

프론트가 없으면 인증된 `/github/connection-result` 임시 페이지를 쓸 수 있다.
로컬 HTTP는 정확한 loopback 호스트에서만 지원한다. 공개 backend와 localhost 사이에서는
세션 쿠키가 공유되지 않는다. 로컬 callback과 공개 Webhook은 서로 다른 서버에서 처리될 수 있다.

## 3. API 경로와 성공 상태

아래에서 `G=/api/v2/integrations/github`,
`R=/api/v2/projects/{projectId}/github/repositories/{linkId}`다.
`projectId`, `linkId`, `taskId`, `jobId`는 UUID다. GitHub Issue ID와 Issue number를 구분한다.

| 메서드·경로 | 성공 | 요청/응답 |
| --- | --- | --- |
| GET `G/account` | 200 | 계정 연결 상태 |
| POST `G/authorization-requests` | 201 | body 없음 → `authorizationUrl`, `expiresAt` |
| GET `G/callback` | 303 | backend 전용 code/state/error 처리 |
| DELETE `G/account` | 204 | 활성 프로젝트 연결이 있으면 409 |
| GET `G/installations` | 200 | `page=1`, `limit=20` → items,total,nextPage |
| GET `G/installations/{installationId}/repositories` | 200 | 같은 페이지 규칙 |
| GET `/api/v2/projects/{projectId}/github/repositories` | 200 | 연결 배열, 해제 완료 항목 제외 |
| POST `/api/v2/projects/{projectId}/github/repositories` | 201 | installationId,repositoryId + Idempotency-Key → 연결 |
| GET `R` | 200 | 연결·접근 상태·revision |
| DELETE `R` | 202 | query revision, disposition=convert 또는 purge → jobId |
| GET `R/issues` | 200 | 수집된 Issue 페이지 |
| GET `R/labels` | 200 | 라벨 페이지 |
| POST `R/issue-fetches` | 202 | issueNumber + Idempotency-Key → jobId |
| GET `R/import-settings` | 200 | 설정 전체 |
| PUT `R/import-settings` | 200 | revision,enabled,labelIds,assigneeIds → 설정 전체 |
| GET `R/status-mapping` | 200 | 동일한 설정 응답 |
| PUT `R/status-mapping` | 200 | revision,todoLabelId,doingLabelId,doneLabelId → 설정 전체 |
| POST `R/task-import-previews` | 200 | issueIds 생략 시 현재 조건; limit,afterIssueId 선택 |
| POST `R/status-mapping-previews` | 200 | 후보 라벨 ID 3개; limit,afterIssueId 선택 |
| POST `R/task-imports` | 202 | revision,issueIds + Idempotency-Key → jobId |
| PATCH `R/sync-settings` | 200 | revision,paused → 설정 전체; 재개 작업은 비동기 |
| POST `R/sync-requests` | 202 | body 없음, Idempotency-Key → jobId |
| GET `R/sync-status` | 200 | 수집 범위, 중지/프로젝트 상태, baseline, 최신 작업 |
| POST `R/cleanup-requests` | 202 | revision,disposition:"purge" → jobId |
| GET `G/jobs/{jobId}` | 200 | 작업 상태·진행 결과 |
| GET `/api/v2/tasks/{taskId}/github-issue` | 200 | 접근 가능한 원본 메타데이터 |
| DELETE `/api/v2/tasks/{taskId}/github-issue` | 204 | query revision → 일반 Task 전환 |

`POST /api/integrations/github/webhooks`는 GitHub 서버 전용이다. 프론트에서 호출하지 않는다.
raw body HMAC 검증 후 영속 수신에 성공해야 202를 반환한다. 브라우저 세션/CSRF 예외는 이 POST뿐이다.

### 계정·저장소 응답

```json
{
  "status": "connected",
  "githubUserId": 12345,
  "login": "example-user",
  "reauthorizationRequired": false,
  "installationUrl": "https://github.com/apps/example-app/installations/new"
}
```

계정 status는 `not_configured`, `disconnected`, `connected`, `reauthorization_required`다.
연결되지 않았거나 비활성인 경우 ID/login/installationUrl 일부는 null일 수 있다.
installation 항목은 `id,accountLogin,accountType,suspended,issuesReadable`,
repository 항목은 `id,fullName,htmlUrl,privateRepository,issuesEnabled`다.
`page`는 1~100, `limit`은 1~100, `nextPage=null`이면 마지막이다.

```json
{
  "id": "00000000-0000-4000-8000-000000000001",
  "projectId": "00000000-0000-4000-8000-000000000002",
  "installationId": 101, "repositoryId": 202,
  "fullName": "team/demo", "htmlUrl": "https://github.com/team/demo",
  "privateRepository": true, "status": "initializing", "accessStatus": "verified",
  "paused": false, "revision": 1,
  "accessExpiresAt": "2026-10-19T03:01:00Z",
  "createdAt": "2026-10-19T03:00:00Z", "updatedAt": "2026-10-19T03:00:00Z"
}
```

연결 status는 `initializing/ready/disconnecting/disconnected/failed`다.
사용자 중지는 별도의 `paused`이고, 프로젝트 보관은 sync-status의 `projectStatus`로 구분한다.
accessStatus는 `verified/check_pending/denied/reauthorization_required`다.
접근이 확인되지 않으면 fullName/htmlUrl이 null이다. 이전 캐시의 이름·원문을 계속 표시하지 않는다.

## 4. Issue 선택과 수집 범위

`GET R/issues` query: `state=open|closed` 또는 생략, `labelIds`, `assigneeIds`,
`limit=20`(1~100), `cursor`. ID 목록은 쉼표로 전달할 수 있다.
라벨은 ALL, 담당자는 ANY, 두 그룹은 AND다. 각 ID 목록은 최대 20개다.

응답은 `items,total,nextCursor,coverage`다. Issue 항목은
`id,number,title,body,state,reason,htmlUrl,labelIds,assigneeIds,contentHash,contentOverflow,updatedAt`다.
body null은 빈 문자열로 정규화되고 PR은 제외된다. 가져오기는 **id**, 오래된 Issue 조회는 **number**를 사용한다.
라벨 응답은 `items:[{id,name,color}],total,nextCursor`다. 매핑과 조건에는 이름 대신 id를 저장한다.

coverage는 `state,lastSuccessfulSyncAt,closedUpdatedWithinDays`다.
`collecting`은 초기 수집 중, `initial_scope_collected`는 초기 범위 수집 완료다.
초기 범위는 모든 open Issue와 최근 90일에 갱신된 closed Issue다.
total을 저장소 전체 역사적 Issue 수로 표시하지 않는다. 범위 밖 closed Issue는
`POST R/issue-fetches`에 `{"issueNumber":42}`를 보내 조회 작업 완료 후 선택한다.

서명 cursor는 필터/접근 범위와 결합된다. 필터 변경 또는 409 scope 변경 시 기존 cursor를 폐기한다.
페이지 사이의 완전한 시점 고정은 보장하지 않는다.

## 5. 설정, 미리보기와 가져오기

설정 읽기/쓰기 응답은 다음 공통 형태다.

```json
{
  "revision": 2, "enabled": true, "generation": 2, "baselineReady": false,
  "criteria": {"labelIds": [10], "assigneeIds": []},
  "mapping": {"todoLabelId": 10, "doingLabelId": 20, "doneLabelId": null},
  "paused": false
}
```

자동 가져오기는 기본 off다. PUT 요청은 중첩하지 않고
`{"revision":1,"enabled":true,"labelIds":[10],"assigneeIds":[]}`로 보낸다.
빈 조건은 모든 Issue와 일치한다. 설정 변경/off→on/재개는 새로운 기준 상태를 만든다.
초기 수집이 끝나기 전에는 첫 baseline이 완료되지 않는다.
그 시점에 이미 조건에 맞는 Issue는 자동으로 가져오지 않는다. 사용자가 미리보고 명시적으로 선택한다.
이후 관측된 비일치→일치 전환 또는 baseline 후 처음 관측한 일치 Issue가 자동 대상이다.
삭제/일반 전환/정리의 제외 기록은 설정 세대가 바뀌어도 유지된다.

선택 미리보기 요청: `{"issueIds":[501,502],"limit":20}`.
현재 조건 미리보기 요청: `{"limit":20}`.
다음 페이지는 응답의 `nextAfterIssueId`를 `afterIssueId`로 보낸다.
이 값은 서명 cursor가 아닌 seek 위치다. 매번 현재 접근 검증을 받으며 필터 변경 시 처음부터 조회한다.

```json
{
  "items": [{
    "issueId": 501, "issueNumber": 42, "taskId": null,
    "status": "todo", "statusReason": null, "conflictLabelIds": [],
    "errorCode": null, "matches": true, "excluded": false
  }],
  "total": 1, "nextAfterIssueId": null, "revision": 2, "baselineReady": true
}
```

미리보기는 예약이 아니다. 가져오기 요청은
`{"revision":2,"issueIds":[501]}`와 Idempotency-Key를 보낸다. Issue는 최대 100개다.
백엔드는 요청 이후 원문 수집 버전을 확인하고 적용 시 접근/프로젝트/중지/설정 revision을 다시 검사한다.
첫 가져오기에서 길이 초과, 이미 가져옴, 휴지통, 원본 없음 등은 항목별 실패가 될 수 있다.
새 Task의 priority는 normal, tag는 빈 문자열이다.
명시적 가져오기는 제외된 원본을 다시 새 Task로 가져올 수 있지만 기존 일반 Task와 합치지 않는다.

저장소당 queued 가져오기 작업은 최대 10개다. 429일 때 완료를 기다린 뒤 재시도한다.
동일 작업의 네트워크 재시도에는 같은 key/body를 사용한다. key의 길이는 1~128 ASCII 문자,
보관 기간은 24시간이다. 다른 body를 같은 key로 보내면 409다.
새 시도·다른 선택에는 새 key를 사용한다. 설정 저장은 revision 기반이며 같은 값을 반복 저장해도
새 기준 상태가 만들어질 수 있으므로 변경 없이 중복 전송하지 않는다.

## 6. 라벨 상태 매핑과 Task 표시

PUT `R/status-mapping` 예:
`{"revision":2,"todoLabelId":10,"doingLabelId":20,"doneLabelId":30}`.
ID는 서로 달라야 하고 현재 저장소의 라벨이어야 한다. null은 해당 매핑을 해제한다.
Done 매핑은 선택이다. 후보 영향 미리보기 POST는 revision 없이 같은 라벨 ID들과 페이지 옵션을 보낸다.
저장 후 기존 Task 재계산은 비동기다. GitHub 라벨/Issue를 수정하는 API는 제공하지 않는다.

| 원본 상태 | Task 판정 |
| --- | --- |
| closed + completed | done, 라벨보다 우선 |
| closed + not_planned | 마지막 상태 유지, 처음이면 todo; 별도 사유 표시 |
| closed + 알 수 없는 종료 사유 | 마지막 상태 유지/처음 todo, unknown_closure |
| open + 유효한 매핑 라벨 하나 | 해당 todo/doing/done |
| open + 매핑 라벨 여러 개 | 마지막 상태 유지/처음 todo, mapping_conflict |
| open + 매핑 라벨 없음 | todo |
| 삭제된 매핑이 이전 판정에 영향을 줌 | 해당 항목만 유지, mapping_error |

재개방도 open 규칙으로 다시 계산한다. 충돌은 원본 GitHub에서 라벨을 정리하거나 매핑을 수정해 해결한다.
not_planned를 무조건 todo로 바꾸지 않는다. 기존 done을 유지할 수 있다.
통계는 보드 상태별 개수이며 취소를 제외한 완료 성과 지표가 아니다.

기존 Task 응답과 보드의 TaskSnapshot에 `source`가 추가됐다. 일반 Task는 null이다.

```json
{
  "type": "github", "linkId": "00000000-0000-4000-8000-000000000001",
  "issueId": 501, "issueNumber": 42, "repository": "team/demo",
  "htmlUrl": "https://github.com/team/demo/issues/42",
  "statusReason": "mapping_conflict", "conflictLabelIds": [10,20],
  "contentError": null, "issueState": "open", "issueStateReason": null,
  "paused": false, "sourceUpdatedAt": "2026-10-19T03:00:00Z",
  "lastAppliedAt": "2026-10-19T03:00:02Z", "editableFields": ["priority","tag"]
}
```

이 예제는 Task 응답 전체가 아니라 `source` 객체다.
source가 있으면 제목·설명·상태·프로젝트 이동·보드 드래그/완료 버튼을 비활성화한다.
PATCH에는 revision과 수정한 priority/tag만 보낸다. 원본 관리 필드를 같은 값으로 보내도
`TASK_SOURCE_MANAGED` 409다. `GitHub에서 수정` 버튼은 htmlUrl을 연다.
새 창에는 noopener/noreferrer를 사용하고 링크 scheme/host를 검증한다.

`GET /api/v2/tasks`와 `/api/v2/tasks/stats`는 `sourceReason` 필터를 지원한다:
`mapping_conflict`, `not_planned`, `unknown_closure`, `mapping_error`, `content_limit_exceeded`.
미지정이면 전체다. 기존 stats의 status/deleted 필터 금지는 유지된다.

제목 160, 본문 10,000 UTF-16 단위가 상한이다. 처음 초과하면 Task를 만들지 않는다.
연결 후 초과하면 마지막 정상 제목/본문을 유지하고 `contentError=CONTENT_LIMIT_EXCEEDED`를 표시한다.
상태 등 독립 정보는 반영될 수 있다. 본문이 최신이라고 표시하거나 프론트에서 조용히 잘라 저장하지 않는다.
원문 변경 또는 명시적 재시도로 복구한다. 자동 실패는 같은 내용 hash로 반복 생성하지 않는다.
Markdown은 안전한 renderer와 sanitizer를 사용하고 raw HTML/스크립트 실행을 허용하지 않는다.

## 7. 작업 관찰, revision과 새로고침

202 응답은 `{"jobId":"00000000-0000-4000-8000-000000000003"}`다.
GET `G/jobs/{jobId}` 예:

```json
{
  "id": "00000000-0000-4000-8000-000000000003",
  "linkId": "00000000-0000-4000-8000-000000000001",
  "state": "partial", "stage": "0", "page": 1, "attempts": 0,
  "errorCode": null, "createdAt": "2026-10-19T03:00:00Z",
  "updatedAt": "2026-10-19T03:00:05Z", "completedCount": 1, "failedCount": 1,
  "items": [
    {"issueId":501,"state":"completed","taskId":"00000000-0000-4000-8000-000000000004","errorCode":null},
    {"issueId":502,"state":"failed","taskId":null,"errorCode":"CONTENT_LIMIT_EXCEEDED"}
  ], "kind": "import"
}
```

kind는 `collect/issue/apply/baseline/mapping/import/detach`다.
state는 `queued/running/completed/failed/partial`다. 짧은 DB 작업은 running을 관측하지 않을 수 있다.
completed/failed/partial은 해당 시도의 종결 상태다. stage/page는 내부 수집 진행 정보이며 파싱해 제어하지 않는다.
import 결과는 최대 100개 전체를 반환하며 결과 cursor는 없다. 다른 종류는 items가 비어 있을 수 있다.
백그라운드 작업은 최대 8회 재시도 및 backoff를 사용한다. 수집이 갱신되지 않은 선택 항목은
15분 뒤 `GITHUB_SOURCE_NOT_FRESH`로 실패할 수 있다.

화면이 보일 때만 간격을 두고 재조회하고 종료 상태에서는 polling을 멈춘다.
sync-status는 `coverage,latestJob,paused,projectStatus,baselineReady,lastTaskAppliedAt,
latestCollectionJob,latestTaskJob`를 반환한다. 각 Job 상세의 items는 작업 API로 확인한다.
작업이 없으면 latest 필드는 null이며 이를 화면의 대기 상태로 표시한다.

Task 편집 revision과 연결 설정 revision을 섞지 않는다. 설정·중지·해제에는 최신 연결 revision을,
Task 편집·복원·전환에는 최신 Task revision을 사용한다. 409이면 다시 조회하고 사용자 변경을 재확인한다.
새 데이터가 반영됐는지 확인하려고 revision을 임의로 증가시키지 않는다.

기존 Task/project/overview/widget API의 `X-Workspace-Data-Revision`은 문자열로 취급한다.
GitHub API는 이 header를 반환하지 않는다. 작업 종료 후 관련 데이터를 재조회한다.
원문 관측 시각은 업무 값이 같으면 Task/dataRevision 변화 없이 갱신될 수 있다.
layoutRevision은 GitHub 동기화로 변경되지 않는다.
화면 진입·포커스 복귀·수동 새로고침에서 backend를 다시 조회한다.
Webhook 도착만으로 열린 브라우저 화면이 자동 갱신되는 구조는 아니다.

수동 sync-requests와 issue-fetches는 저장소별 60초 cooldown을 공유한다.
중지 중에도 원문 수집 요청은 가능하지만 Task 반영을 우회하지 않는다.

## 8. 중지, 삭제·복원과 연결 해제

중지 요청: `PATCH R/sync-settings {"revision":3,"paused":true}`.
재개는 paused=false다. 원문 수집과 권한 철회 처리는 중지하지 않는다.
프로젝트 archive도 Task 반영을 멈추지만 사용자 paused 값을 바꾸지 않는다.
reactivate 후에도 사용자 중지는 유지된다. 재개/재활성화는 기준 상태와 기존 Task 대조를 다시 수행한다.
그동안 쌓인 신규 후보는 미리보기 후 명시적으로 선택한다.

Task 삭제는 기존 soft-delete API를 쓴다. 자동 재생성 제외 기록과 원본 연결을 유지한다.
휴지통에서도 접근 검증과 원본 관리 제한이 적용된다. 복원은 기존 restore API를 사용하며
같은 Task를 복원하고 최신 원문 수집을 예약한다. 즉시 응답이 최신 GitHub 본문이라는 뜻은 아니다.

| 동작 | 결과와 화면 처리 |
| --- | --- |
| Task의 github-issue DELETE | 현재 접근 확인 후 일반 사본으로 전환, 자동 재생성 제외; 휴지통은 복원 후 편집 |
| 저장소 DELETE disposition=convert | 50개씩 접근 가능한 원본 Task를 일반 사본으로 전환 |
| 저장소 DELETE/cleanup disposition=purge | 남은 원본 관리 Task를 영구 삭제하고 캐시 정리; 복원 불가 |
| GitHub 계정 DELETE | 활성 연결이 남으면 409; 정리 후 로컬 자격증명 제거 |
| GitHub App 제거/권한 철회 | 접근 차단·동기화 중단; 자동으로 일반 Task로 바꾸지 않음 |

해제 요청 즉시 disconnecting으로 표시한다. 조회에서 남은 보호 Task는 제외한다.
진행 중 창을 닫아도 작업은 영속된다. 권한/원본 문제로 partial이면 완료된 사본과 남은 보호 항목을 구분한다.
해제 job은 접근을 잃어도 소유자에게 진행 metadata만 제공하며 원문/개별 목록을 제공하지 않는다.
현재 revision으로 같은 해제를 다시 요청해 남은 처리를 재개하거나, 명시적인 purge 확인 화면을 제공한다.
새 해제 요청은 이전 진행 시도를 대체한다. 이미 일반 사본으로 전환한 Task는 purge 대상이 아니다.
해제 완료 연결은 목록에서 사라진다. 재연결하면 기존 linkId를 재사용하고 설정을 초기화하며 제외 기록은 유지한다.
원격 App 제거와 서비스에서 저장소 연결을 해제하는 것은 별개다.

## 9. 접근·오류에 따른 UI 상태

접근 grant는 60초다. 목록은 요청당 만료 연결 하나만 갱신할 수 있으므로 나머지는 pending일 수 있다.
외부 확인 실패 때 만료된 grant를 연장하지 않는다. GitHub의 철회를 즉시 알 수 있다는 보장은 없다.
이미 권한이 있을 때 전달된 내용이나 전환한 일반 사본을 원격 회수하지는 않는다.

Task 목록/stats/overview에 `externalDataUnavailable`이 추가됐다.
보드 위젯은 statistics, overview 위젯은 tasks 통계 안에 같은 필드가 있다.
true이면 표시 가능한 결과만의 total/count다. 숨겨진 항목 개수를 추정하거나 이전 항목을 합산하지 않는다.

| 상태/오류 | 동작 |
| --- | --- |
| total=0, externalDataUnavailable=false | 정상 빈 결과 |
| externalDataUnavailable=true | 일부 외부 데이터 표시 불가 안내, 가능한 일반 Task는 계속 표시 |
| check_pending | 확인 중 안내, 수동 재조회; 이전 private 내용 가림 |
| reauthorization_required | GitHub 재연결 안내, 서비스 로그인과 구분 |
| denied 또는 원본 상세 404 | 캐시 원문 제거, 접근/설치 저장소 설정 확인 |
| paused / projectStatus=archived | 사용자 중지/프로젝트 보관 사유를 따로 표시 |
| initializing / baselineReady=false | 수집/기준 설정 중, 자동 가져오기 활성 완료로 표시하지 않음 |
| 401 AUTH_REQUIRED | 서비스 로그인 |
| 403 CSRF_INVALID / ACCOUNT_DISABLED | CSRF 재발급·Origin 확인 또는 계정 상태 안내 |
| 409 REVISION_CONFLICT | 최신 설정/Task 재조회 |
| 409 TASK_SOURCE_MANAGED | 원본 관리 필드 편집 금지, GitHub로 이동 |
| 409 TASK_ACCESS_SCOPE_CHANGED / GITHUB_ACCESS_SCOPE_CHANGED | cursor·이전 목록 폐기, 첫 페이지 재조회 |
| 409 GITHUB_LABEL_UNAVAILABLE / GITHUB_SYNC_PAUSED | 라벨/중지 상태 재조회 |
| 409 IDEMPOTENCY_KEY_REUSED | 다른 작업에 재사용한 key를 수정 |
| 429 GITHUB_RATE_LIMITED | Retry-After가 있으면 준수; 자동 반복 요청 금지 |
| 429 GITHUB_IMPORT_QUEUE_FULL / GITHUB_LINK_LIMIT_EXCEEDED | 대기 작업 완료 또는 연결 수 정리 |
| 503 GITHUB_NOT_CONFIGURED / GITHUB_UNAVAILABLE | 설정/외부 장애 안내, 일반 Task 기능 유지 |

공통 오류는 `{"code":"REVISION_CONFLICT","message":"...","fieldErrors":{},"requestId":"..."}`다.
프론트 분기는 message 대신 code로 한다. 진단 문의에는 requestId를 사용한다.
job item 오류에는 `ALREADY_IMPORTED`, `TASK_IN_TRASH`, `CONTENT_LIMIT_EXCEEDED`,
`GITHUB_ISSUE_UNAVAILABLE`, `GITHUB_SOURCE_NOT_FRESH`, `GITHUB_SOURCE_CHANGED`가 있다.
휴지통 항목은 새 생성 대신 복원을 안내한다. 설정 변경/중지 이전 작업이 실패하면 현재 설정으로 다시 선택한다.

설치 목록이 비면 설치 URL을 제공하고 조직 승인·선택 저장소·권한을 확인하도록 안내한다.
backend는 별도의 `approval_pending` 값을 반환하지 않으므로 승인 대기라고 단정하지 않는다.
계정 교체는 프로젝트 연결 정리 → 계정 DELETE → 새 사용자 동의 순서다.

## 10. 프론트 수용 테스트와 실제 검증 한계

- 서비스 로그인/CSRF 없이 mutation이 거부되고 callback 복귀 후 계정 API로 결과를 확인한다.
- 두 저장소를 같은 프로젝트에 표시하고 ID 기준으로 라벨을 저장한다.
- 초기 일치 Issue는 자동 생성되지 않으며 선택 가져오기/추후 조건 전환만 새 Task를 만든다.
- 같은 요청 재전송은 같은 job으로 처리하고 부분 성공은 항목별로 표시한다.
- 원본 관리 Task는 드래그·제목·본문·프로젝트 편집이 막히고 priority/tag는 수정된다.
- 다중 라벨 충돌, not_planned, 길이 초과, 정상 복귀를 서로 다른 상태로 표시한다.
- 권한을 잃으면 detail/list/stats/widget/trash의 캐시가 가려지고 일반 Task는 계속 사용할 수 있다.
- scope 변경 뒤 기존 cursor를 폐기한다. 작업 종료/포커스 복귀 후 재조회한다.
- 중지→프로젝트 archive/reactivate에서 사용자 중지가 유지되고 재개 후보를 명시적으로 선택한다.
- 해제의 50개 단위 진행, 부분 중단, 재개, 보호 데이터 purge를 별도로 확인한다.
- Markdown에 HTML·위험 링크가 있어도 실행되지 않고 비밀/원문이 오류 로그에 남지 않는다.

자동 검증은 PostgreSQL과 모의 GitHub API, 실제 로컬 HTTP Webhook 수신을 포함한다.
실제 GitHub에서 App JWT 인증에 이어 사용자 OAuth, 개인 설치/저장소 조회, 프로젝트 연결,
Issue 가져오기, 수동 동기화를 통한 닫기/재개방과 세 상태 라벨 매핑을 확인했다.
근거는 [사용자 실행 수동 검증](../../reviews/plan0017/live-manual-validation.md)이다.
로컬 기능은 활성화됐고 Webhook은 비활성 상태다. 자동 가져오기, 중지/재개, 조직 승인·철회,
공개 Webhook 전달 등은 실제 검증이 남아 있다. 자동 가져오기 설정 적용 여부는 재조회해야 한다.
이 문서는 프론트 구현 안내이며 프론트 화면 구현이나 운영 배포 완료를 의미하지 않는다.
