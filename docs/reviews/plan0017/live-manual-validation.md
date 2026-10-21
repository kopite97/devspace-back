# 실제 GitHub 수동 검증 (2026-10-19)

사용자가 로컬 IDE 서버와 로그인된 Swagger에서 실행하고 대화에 제공한 응답을 근거로 기록한다.
에이전트가 브라우저를 직접 제어한 결과가 아니다. 비밀키, 쿠키, OAuth code/state는 기록하지 않는다.
로컬 origin과 callback은 `http://127.0.0.1:8080`으로 통일하고 GitHub 기능을 활성화했다.
Webhook은 사용자 요청에 따라 비활성 상태로 두고 수동 동기화로 검증했다.

## 통과한 흐름

- 사용자 OAuth: 연결 성공 페이지 표시. 이후 인증된 설치/저장소 API 조회 성공.
- 개인 계정 설치 조회: 활성 설치 1개, Issues read 권한 확인.
- 선택 저장소 조회와 프로젝트 연결: 접근 verified, 초기 수집 완료.
- Issue 2개와 기본 라벨 10개 조회, PR이 아닌 Issue #1의 가져오기 미리보기 성공.
- 명시적 가져오기: 작업 completed, 성공 1건/실패 0건. 새 Task 제목/본문/원본 정보 확인.
- Issue를 completed로 닫은 뒤 수동 동기화: Task todo → done, revision 2.
- 재개방 후 수동 동기화: Task done → todo, 원본 open/reopened, revision 3.
- 라벨 3개 추가·수집 및 상태 매핑 저장: 설정 revision 2.
- InProgress → doing (Task revision 4), 열린 Issue의 Done → done (revision 5),
  Backlog → todo (revision 6). 각 응답의 충돌 목록은 비었고 반영 오류는 없었다.
- 처음 종료 검증에서 원본 Issue가 아직 Open이어서 Task도 todo였다.
  실제로 닫은 뒤 정상 반영됐으므로 이 결과를 동기화 결함으로 기록하지 않는다.

## 다음 검증 시작점

마지막으로 확인된 설정은 자동 가져오기 enabled=false, 설정 revision=2, paused=false다.
자동 가져오기 활성화 요청은 안내했지만 실행 응답을 받지 않았으므로 적용 여부는 미확인이다.
재개할 때 import-settings와 sync-status를 먼저 조회하고 현재 revision을 사용한다.
아직 가져오지 않은 Issue #2로 기준 상태 완료 이후 조건 비일치 → 일치 전환을 검증한다.

남은 실제 검증: 자동 가져오기, 중지/재개, 라벨 충돌/예외, 계정 교체/재인증,
조직 승인과 접근 철회, 연결 해제/복구, 공개 Webhook 전달과 누락 복구.
Swagger는 위 작업에 사용한 operation만 사용자 실행으로 확인했다. 전체 UI 점검은 미완료다.
사용자는 남은 검증을 추후 진행하기로 했다. PLAN-0017은 active로 유지한다.

## 2026-10-20 배포 검증 재개

- 사용자 요청으로 남은 검증을 재개했다. SSH를 통해 배포 컨테이너를 확인했다.
- `devspace-back`이 `GitHub key configuration is invalid`로 재시작 중이었다.
  환경변수는 호스트 PEM 경로를 가리켰으나 해당 파일의 컨테이너 마운트가 없었다.
- Compose에 PEM 단일 파일 읽기 전용 마운트와 컨테이너 경로 override를 추가했다.
  호스트 키의 소유권/모드를 컨테이너 UID/GID 10001:10001, 600으로 제한했다.
  같은 이미지를 사용해 app만 재생성했으며 DB 컨테이너는 변경하지 않았다.
- Compose `config -q`, 컨테이너 내부 키 읽기 검사, Spring Boot 시작 로그를 확인했다.
  이 결과는 GitHub JWT 인증이나 실제 Issue/Webhook 처리 성공을 의미하지 않는다.
- 공개 origin의 callback/return URL 설정을 확인했다. return URL은
  `/github/connection-result`이나 프론트 nginx는 `/api`, `/oauth2`만 프록시한다.
  임시 backend 결과 페이지용 정확한 경로 프록시 또는 실제 frontend 결과 경로가 필요하다.
  프론트 설정은 변경하지 않았다.
- 공개 서비스 브라우저 접속은 사용자 거절로 도구의 보안 승인 단계에서 차단됐다.
  우회 접속하지 않았으며 로그인 세션, Swagger UI, 실제 API 흐름 검증은 미실행이다.
- 이전 Webhook 비활성 선택의 변경 여부는 확인 대기다. 원격 App 설정은 변경하지 않았다.
  PLAN은 active이며 남은 실제 검증 항목은 체크하지 않는다.

## 2026-10-20 공개 브라우저 접근 재확인

- 에이전트가 Chrome에서 `https://devspace.nellucia.dev` 접근과 Google 로그인 후
  작업실·프로젝트 화면 표시를 확인했다. 앞선 브라우저 접근 차단은 이번 실행에서 해소됐다.
- 같은 브라우저 세션으로 `GET /api/v2/integrations/github/account`를 조회했다.
  응답은 `status=disconnected`, `reauthorizationRequired=false`였다.
- 현재 프로젝트의 GitHub 저장소 연결 조회 응답은 빈 배열이었다.
  이전 로컬 검증의 계정·저장소 연결이 공개 환경에도 존재한다고 가정하지 않는다.
- `/swagger-ui/index.html`은 SPA의 `Page not found`를 표시했다.
  사용자가 배포 Swagger를 의도적으로 비활성화했다고 확인했으므로 결함이나 수정 대상으로
  취급하지 않는다. 이 결과로 Swagger 전체 렌더링 검증을 완료 처리하지 않는다.
- `/github/connection-result` 역시 SPA의 `Page not found`를 표시했다.
  사용자가 프론트 GitHub 연동은 아직 구현하지 않았다고 확인했다. PLAN의 프론트 구현
  제외 범위를 유지하며 프론트 기능을 추가하거나 공개 프록시를 변경하지 않았다.
- 이번 실행은 로그인 및 읽기 전용 API 확인까지다. GitHub OAuth, 저장소 연결,
  자동 가져오기, 중지·재개, Webhook 검증을 새로 통과한 것으로 기록하지 않는다.
  기존 미커밋 PEM 마운트·배포 문서 변경은 유지했다. 코드 변경이 없어 빌드를 반복하지 않았다.
- 후속 검증 환경 선택은 사용자 확인 대기다. 기존 로컬 Swagger에서는 이전 연결의
  import-settings/sync-status를 먼저 재조회한다. 공개 환경을 사용한다면 계정 연결 진입과
  결과 확인 수단 및 격리된 테스트 자원을 먼저 마련해야 한다.

## 2026-10-20 로컬 브라우저 후속 검증

사용자의 진행 요청에 따라 에이전트가 기존 로컬 환경을 기동하고 Chrome의 로그인된
Swagger UI에서 실제 HTTP 요청을 실행했다. 아래 상태가 앞선 revision=2 재개 지점을 대체한다.

### 환경과 재개

- 기존 `devspace-back-postgres-1` 컨테이너와
  `devspace-back-postgres-local-20261013` 볼륨을 사용했다. DB는 localhost:5433/devspace다.
  데이터 초기화 없이 39개 Flyway migration 검증과 V39 no-op을 확인했다.
- 기존 `build/libs/devspace-0.0.1-SNAPSHOT.jar`를 Java 21로 실행했다.
  `.env`의 Windows 경로가 properties 해석에서 절대 경로로 유지되지 않아 최초 시작이
  실패했다. 기존 경로를 실행 프로세스의 `FILE_STORAGE_ROOT`와
  `GITHUB_PRIVATE_KEY_PATH` 환경변수로 전달한 뒤 정상 시작했다. `.env`는 변경하지 않았다.
- `http://127.0.0.1:8080`에서 서비스 로그인 후 GitHub 계정 connected,
  재인증 불필요 상태와 기존 `Test` 프로젝트의 `kopite97/Test-Repo` 연결
  ready/verified를 확인했다. 배포 환경과 별도 DB/세션이다.
- 로컬 Swagger의 GitHub operation 목록과 중지·재개/자동 가져오기 요청·응답 UI를
  확인했다. 모든 operation의 펼친 schema/응답 렌더링을 검사한 것은 아니다.

### 실제 HTTP 결과

| 검증 | 결과 |
| --- | --- |
| 초기 import-settings/sync-status 조회 | revision=2, enabled=false, paused=false, baselineReady=false |
| PATCH sync-settings, revision=2, paused=true | 200, revision=3, generation=2, paused=true |
| 오래된 revision=2로 paused=false 요청 | 409 REVISION_CONFLICT |
| 현재 revision=3으로 paused=false 요청 | 200, revision=4, generation=3 |
| 재개 후 비동기 상태 조회 | baseline 작업 completed, baselineReady=true, paused=false |
| PUT import-settings, revision=4, Backlog 라벨 조건 활성화 | 200, revision=5, generation=4; baseline 작업 completed |
| 활성화 후 Task 목록 | Issue #1의 기존 Task revision=6/todo 유지, Issue #2 미생성; 일반 Task 포함 total=2 |
| PUT import-settings, revision=5, enabled=false 및 빈 조건 | 200, revision=6, generation=5; 후속 조회 baselineReady=true |
| 세션 없는 별도 HTTP의 GitHub account 조회 | 401 |

재개 baseline 작업 ID: `eb1f582d-a891-41c7-a825-123ee7a3a854`.
활성화 baseline 작업 ID: `82dae4cc-9e43-4619-adc9-70f2fd1e6b4c`.
이번 검증은 기존 수집 데이터로 기준 상태를 처리한 결과다. `lastSuccessfulSyncAt`은
2026-10-19의 기존 값이며 새로운 원격 Issue 수집 성공으로 해석하지 않는다.

### 최종 상태와 남은 검증

- 최종 설정은 revision=6, enabled=false, paused=false, 빈 라벨/담당자 조건,
  기존 세 상태 라벨 매핑 유지다. [최종 설정 화면](local-settings-restored.jpg)을 저장했다.
  중지/활성화 전환에 따른 generation과 baseline 메타데이터 변경은 남아 있다.
- GitHub Issue #2 브라우저 접근은 사용자 거절로 브라우저 보안 승인 단계에서 차단됐다.
  다른 도구나 경로로 우회하지 않았고 원격 Issue·라벨·App 설정을 변경하지 않았다.
- 조건 비일치 → 일치에 따른 실제 자동 Task 생성, 중지 중 원격 변경 후 최신 원본 반영,
  라벨 충돌/예외, 계정 교체·재인증, 조직·접근 철회, 해제·복구, Webhook 전달·복구는 남아 있다.
  중지·재개 API/baseline 성공을 이 전체 시나리오의 완료로 표시하지 않는다.
- 후속 실행을 위해 로컬 서버(PID 26592), 기존 DB, Swagger 탭을 유지했다.
  Swagger는 새로고침하여 입력한 CSRF 값과 요청 결과를 정리했다. 비밀은 기록하지 않았다.
- 소스 변경이나 새 빌드는 없으며 문서 변경의 `git diff --check`를 확인했다.
  PLAN은 active를 유지한다.

## 2026-10-21 실제 GitHub 변경 검증

### 범위와 환경

- 사용자가 `kopite97/Test-Repo` Issue·라벨 변경, Webhook 활성화, 설치 일시 중지·복원을 승인했다.
- 사용자 실행 backend 8080과 기존 DB를 유지했다. 기존 frontend를 5173에서 실행해
  허용된 Origin의 세션/CSRF 요청으로 검사했다. CSRF 응답 필드는 `csrfToken`이다.
  초기 검사 도구가 잘못된 필드를 읽어 발생한 403은 올바른 필드 사용 후 해소됐다.
- App `kopite-test-app`(5374777), 설치 172869880은 Issues/Metadata read,
  선택 저장소 Test-Repo 하나다. App은 private이며 별도 조직·시험 계정은 제공되지 않았다.
- Webhook은 확인 당시 이미 Active, Issues/Label 구독 및 SSL 검증 활성 상태였다.
  URL은 `https://devspace.nellucia.dev/api/integrations/github/webhooks`다.
  Webhook 설정은 변경하지 않았다. 공개 receiver와 로컬 DB는 별개다.

### 실제 API 및 원격 변경 결과

| 검증 | 실제 결과 |
| --- | --- |
| 초기 상태 | 설정 revision=8, enabled=false, paused=false, Task 2개 |
| Backlog 자동 가져오기 활성화 | revision=9, baseline 완료; 기존 Issue 소급 생성 없음 |
| Issue #2에 Backlog 추가 후 수동 수집 | 새 Task 1개 자동 생성, todo/revision=1 |
| 같은 Idempotency-Key 재전송 | 202 및 같은 jobId; 새 키의 60초 cooldown 요청은 429 GITHUB_RATE_LIMITED |
| Task priority/tag 편집 | 200; 원본 제목 편집은 409 TASK_SOURCE_MANAGED |
| Backlog와 InProgress 동시 매핑 | 기존 todo 유지, mapping_conflict와 충돌 라벨 2개 반환 |
| paused=true 후 Backlog 제거·수집 | 원본에는 InProgress만 반영; Task revision=3/todo와 기존 원본 snapshot 유지 |
| paused=false | Task revision=4/doing, 충돌 해소; priority/tag 유지 |
| Issue #2 close as not planned | Task revision=5/doing 유지, issueStateReason/statusReason=not_planned |
| Issue 재개방·모든 라벨 제거 | Task revision=6/todo, 예외 해소 |
| 설치 Suspend | accessStatus=denied, 저장소 이름/URL null; Task 상세 404 RESOURCE_NOT_FOUND |
| 중지 중 목록/수동 수집 | 일반 Task 1개만 표시, externalDataUnavailable=true; 수집 404 GITHUB_ACCESS_DENIED |
| 설치 Unsuspend | 접근 verified 회복, 수집 202 및 Task 재노출 |
| 신규 시험 Task 원본 연결 해제 | 204, revision=8/source=null인 일반 Task 전환 |
| 신규 시험 Task 휴지통/복원 | 삭제 200 revision=9, 복원 200 revision=10; 최종 휴지통 revision=11 |
| Swagger | GitHub 28개 operation 모두 펼침, 응답 영역 존재 및 Could not render 오류 없음 |

설치 중지 확인창은 자동 처리에 실패하여 사용자가 직접 중지를 적용했다.
실제 denied 응답과 GitHub Unsuspend 버튼을 확인한 뒤 에이전트가 Unsuspend를 실행했다.
복원 후 GitHub Suspend 버튼, verified 응답과 원래 Task 2개 재노출을 확인했다.
설치 제거(uninstall), 사용자 OAuth 철회와는 구분한다.

### 추적 가능한 증거

- baseline: `1320888b-dabd-4f3e-bc3b-fe29ea1973f1`
- 최초 수집/동일 키 재전송: `450f902b-fe31-43a3-959a-13ecacd61af2`
- 조건 전환 자동 생성: `dd02974e-a7f4-4bdf-a686-f31db2d1bc64`
- 라벨 충돌: `72df10fb-0b90-4d89-ab7c-f99b8dc69773`
- 중지 중 원본 수집: `4419377c-d427-4531-b2f4-7e2a3449fb53`
- not_planned: `b52f59c4-4bc0-461d-b58a-f9c61bba5698`
- 설치 복원 후 수집: `f132b610-310d-4c54-b70c-e16f4bce04a3`
- 공개 delivery `c328b390-cd23-11f1-924c-a674a79673ad`:
  2026-10-21T07:48:12Z, Response 탭 HTTP 202, 처리 시간 0.65초.
- 공개 Webhook 캡처는 서명 헤더가 포함되어 저장소에 첨부하지 않는다.
- [Swagger](live-swagger-20261021.jpg),
  [설치 복원](live-installation-restored-20261021.jpg),
  [Issue 복원](live-issue-restored-20261021.jpg).

### 최종 상태와 남은 범위

- 설정 revision=12/generation=11, enabled=false, 빈 labelIds/assigneeIds,
  paused=false, baselineReady=true. 기존 세 상태 라벨 매핑 유지.
- Issue #2는 open, 라벨 없음, 제목/본문 유지. 라벨 변경·닫기·재개방 이력은 남는다.
- 이번에 생성한 Task `ebcff91b-5f6d-4cb4-8cb4-a915c3e31a60`는 일반 Task로
  전환한 뒤 휴지통에 보관했다. 연결 해제에 따른 Issue #2 재가져오기 제외 기록도 남는다.
  원본 연결을 자동 복원할 수 있다고 가정하지 않는다.
- 활성 Task는 기존 2개이며 기존 GitHub Task revision=6, 일반 Task revision=7을 유지한다.
  externalDataUnavailable=false. App 설치와 기존 저장소 연결은 활성 상태다.
- 공개 Webhook 수신 202와 로컬 수동 수집/Task 반영은 각각 검증했다.
  공개 DB의 Task 반영, 전달 중단/재전송 복구를 검증한 것으로 합치지 않는다.
- 조직 승인·사용자 철회·다른 계정 교체에는 별도 시험 자원이 필요하다.
  저장소 일괄 해제/복구, 설치 제거, 공개 수신 중단 복구는 실제 검증이 남아 있다.
  기존 HTTP 모의·PostgreSQL 통합 검증이 대체 근거다. PLAN은 active로 유지한다.
- 소스 변경은 없고 기존 빌드 결과를 재사용했다. 문서 diff 검사를 수행한다.
