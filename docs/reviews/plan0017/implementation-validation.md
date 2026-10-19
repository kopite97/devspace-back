# PLAN-0017 구현 검증

날짜: 2026-10-19. Java 21, PostgreSQL 16.4 Testcontainers, Spring Boot 4.1.1.

## 구현 범위

프로젝트별 복수 저장소, 사용자별 단일 GitHub 계정, GitHub App 인증과 접근 검증,
영속 Webhook/수집/복구 작업, Issue 조회, 선택 가져오기, 조건 전환 기반 자동 가져오기,
라벨 ID 매핑과 미리보기, Task 원본 관리, 중지·재개, 프로젝트 보관·재활성화,
Task 전환과 50개 단위 저장소 해제·보호 데이터 정리를 구현했다.
개발일지, 기존 Task 연결, GitHub 쓰기, PR/Actions, 새 위젯, SSE는 포함하지 않았다.

## 자동 검증

- 전체 `gradlew.bat build`: 365 tests, failures 0, errors 0. 소요 7분 58초.
- suite별 결과: [full-regression-tests.json](full-regression-tests.json).
- 최종 nullable 스키마·미리보기 조건 정합화 후 GitHub/API 집중 테스트 56개가 추가 통과했다
  ([final-focused-tests.json](final-focused-tests.json), 2분 33초). 이 실행은 기존 전체 검증과
  일부 중복되므로 421개 고유 테스트로 합산하지 않는다. 이후 `build -x test`로 최신 artifact를 재생성했다.
- 임의 로컬 HTTP 포트의 실제 서버에서 비로그인 API 401, 서명 Webhook 202/영속 저장,
  변조 서명 401 및 세션 쿠키 미발급을 확인했다. 인증된 브라우저 API 시나리오는 MockMvc를 사용했다.
  실제 브라우저 OAuth와 Swagger UI의 수동 렌더링 확인은 남아 있다.
- V21의 기존 Project/Task 데이터를 V39까지 보존하는 migration 검증,
  최신 schema의 PostgreSQL 백업→별도 DB 복원→Flyway no-op→애플리케이션/Hibernate 검증 포함.
- 기존 API 목록은 GitHub 28개 operation을 추가해 총 88개로 확인했다.
  기존 API의 정확한 응답 필드 목록은 `source`/`externalDataUnavailable` 추가를 반영했다.
- GitHub HTTP 모의 서버에서 응답 크기, 전체 응답 시간 제한, redirect 거부,
  인증 실패, rate limit, 페이지 수집, PR 제외, cursor와 App JWT 요청을 검증했다.
- 수동/자동 경합은 동일 workspace 잠금으로 직렬화하며 같은 Issue의 Task가 하나만 생긴다.
  원문 갱신은 priority/tag를 보존하고 동일 결과 재처리는 Task revision을 올리지 않는다.
- 접근 철회는 SQL 가시성 view에 반영해 detail/page/count/stats/trash와 그 조회를 사용하는
  overview/widget를 보호한다. scope 변경 cursor는 새 첫 페이지를 요구한다.
- 수동 가져오기 최신성은 DB 수집 버전으로 판정한다. 시계 정밀도가 같아도 과거 캐시를
  새 수집 결과로 오인하지 않는다. 초기 수집 완료 전 baseline 활성화를 금지한다.

## 격리된 규모·잠금 검증 범위

51개 Issue를 두 가져오기 batch로 처리하고 50개만 먼저 일반 Task로 전환했다.
남은 원본의 접근 불가를 표시한 뒤 부분 완료에서 멈추는 것을 확인하고,
사용자 접근 철회 후 명시적 purge로 남은 보호 데이터와 캐시를 정리했다.
이 시나리오 전체는 당시 실행에서 1.795초였으며 GitHub 네트워크는 모의 포트였다.
수동/자동 경합 2개 worker 시나리오는 0.103초였다. 운영 처리량 보장이 아니다.

목록의 20개 항목/51개 total과 다음 cursor, 페이지별 Task 수와 무관한 접근 확인,
원격 접근 검증 시 DB transaction이 열려 있지 않음을 검사한다.
본문 수집은 100개 기본 페이지와 4 MiB 응답 상한, Task/해제 batch는 50개,
선택 가져오기는 요청당 100개·저장소당 queued 10개로 제한한다.
지속적인 운영 backlog, 사용자 규모별 지연 최적화와 공정한 작업 배분은 별도 측정 대상이다.

## 실제 외부 검증과 남은 조건

[읽기 전용 App 등록 검증](live-app-registration-check.json)은 실제 GitHub에서 성공했다.
App/Client ID, Issues read, 활성 설치 1개를 확인했고 로컬 slug를 수정했다.
이 검증은 사용자 OAuth Client secret의 유효성이나 Issue-to-Task 전체 흐름을 증명하지 않는다.

사용자가 비활성화한 Webhook과 `GITHUB_ENABLED=false`를 유지했다. 실제 API의 이벤트 목록은
비어 있으며 수신 backend 배포, 구독 설정, Webhook 활성화, 사용자 브라우저의 서비스 로그인과
GitHub 동의가 남아 있다. 조직 승인·사용자/설치 철회·공개 HTTPS 전달은 미검증이다.
따라서 PLAN의 실제 연동 검증 항목과 전체 완료 표시는 미완료로 유지한다.
실사용 데이터 삭제, 원격 App 설정 변경, 운영 배포는 수행하지 않았다.

## 구현 계약의 구체화

- 가져오기 결과는 최대 100개이므로 job 응답에 전체 제한된 결과를 포함한다. 결과 cursor는 없다.
- 미리보기는 `afterIssueId` seek 위치이며 Issue 목록의 서명 cursor와 구분한다.
- 중지/재개 PATCH는 200 설정을 반환한다. baseline/기존 Task 대조는 비동기로 진행한다.
- local Task batch는 외부 검증 이후 짧은 원자 DB transaction을 사용한다.
  네트워크 수집·복구의 lease와 분리해 DB 작업 중 외부 호출을 하지 않는다.
- GitHub API 응답에는 workspace 관측 header를 약속하지 않는다.
  Task/project/overview/widget 재조회로 해당 snapshot의 관측 revision을 얻는다.
- 보호 데이터 purge와 자격증명 제거는 provider 기능 비활성 상태에서도 가능하다.
  purge worker 실행에는 `GITHUB_WORKER_ENABLED=true`가 필요하다.

프론트 handoff: [GitHub App·Issue–Task 안내](../../requirements/frontend/github-app-issue-task-integration.md).
문서의 27개 브라우저 operation과 Webhook 경로를 최종 OpenAPI에 대조하고,
6개 JSON 예제의 문법·최상위 응답 필드를 검사했다. 안내는 실제 검증 한계를 별도로 표시한다.

## 2026-10-19 후속 실제 검증

앞선 등록 검증 당시의 미검증/비활성 상태는 이후 사용자 실행으로 일부 해소됐다.
로컬 GitHub 기능을 활성화하고 OAuth, 개인 설치/저장소 조회, 프로젝트 연결,
Issue 가져오기, 수동 동기화에 의한 닫기/재개방 및 Backlog/InProgress/Done 매핑을 확인했다.
상세 근거와 재개 절차는 [수동 검증 기록](live-manual-validation.md)을 따른다.
Webhook, 자동 가져오기, 중지/재개, 조직·철회·예외 시나리오는 미검증이며 PLAN은 active다.
