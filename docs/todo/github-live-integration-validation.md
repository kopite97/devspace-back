# 실제 GitHub App 연동 검증 환경

상태: App 등록의 읽기 전용 검증 완료, 브라우저 사용자 인증·실제 Webhook 연동은 미검증. PLAN-0017의 필수 검증이며 후속 기능으로 연기하지 않음.
관련: [PLAN-0017 최종 검증](../plans/PLAN-0017-github-app-issue-task-integration.md#final-validation)

## 필요한 환경

격리된 GitHub App, 테스트용 개인·조직 계정과 public/private 저장소,
설치 승인·사용자 접근 변경을 시험할 권한, 외부에서 접근 가능한 HTTPS callback/Webhook이 필요하다.
이 문서는 해당 계정·환경이 이미 준비됐다는 의미가 아니며 운영 App 생성 승인도 아니다.

## 확인 작업

2026-10-19: 제공된 PEM으로 App JWT 인증, App/Client ID 일치, Issues read 권한,
활성 설치 1개를 GitHub API에서 확인했다. 로컬 App slug 불일치는 수정했다.
API가 보고하는 이벤트 목록은 비어 있다. 사용자가 꺼 둔 Webhook Active와
`GITHUB_ENABLED=false`는 유지했다. 비밀을 제외한 결과는
[등록 검증 기록](../reviews/plan0017/live-app-registration-check.json)에 남겼다.
이 결과는 Client secret을 사용하는 사용자 OAuth 교환 성공을 의미하지 않는다.

남은 실행 조건: 수신 backend 배포, 동일 Webhook secret, Issues/Label 및 설치 수명주기
이벤트 설정, 사용자의 명시적 Webhook 활성화, 같은 backend 호스트의 로그인 세션에서
GitHub 사용자 동의. 이후 테스트 저장소에서 아래 흐름을 확인한다.

- [ ] 시험 자원·권한·callback/Webhook 경로와 자격증명 전달 방법을 확인한다.
- [ ] 개인/조직 설치, 승인 대기, 선택 저장소, 사용자 인증과 재인증을 시험한다.
- [ ] 사용자당 계정 하나 제약과 계정 교체, 오래된 Issue의 신규 조건 충족 자동 가져오기, 일시 중지/재개를 시험한다.
- [ ] Issue 가져오기·라벨 변경·닫기·재개방·설치 중지/제거·사용자 철회를 시험한다.
- [ ] 수신 중단 후 전달 복구와 로컬 결과 정합성을 확인한다.
- [ ] 수행한 환경·시나리오·결과·미검증 항목을 비밀 없이 기록한다.

## 완료 기준

환경이 없으면 HTTP 모의 검증까지만 수행 가능하다. 이를 실제 GitHub 연동 성공으로 표시하지 않는다.
필수 실제 검증이 남으면 PLAN의 해당 항목은 체크하지 않고 blocker를 기록한다.
개발 후 frontend 안내에도 검증 범위와 한계를 명시한다.


## 2026-10-19 수동 검증 진행 갱신

사용자가 로컬 Swagger에서 OAuth, 설치/저장소 조회, 프로젝트 연결, Issue 가져오기,
수동 동기화에 의한 닫기/재개방 및 세 상태 라벨 매핑을 검증했다.
이 기록은 앞선 사용자 OAuth/Issue 동기화 미검증 설명을 갱신한다.
Webhook은 비활성으로 유지했다. 자동 가져오기 설정 저장은 응답 미확인이다.
남은 실제 검증은 사용자가 추후 진행하기로 했으므로 전체 완료 항목은 체크하지 않는다.
근거와 재개 절차: [수동 검증 기록](../reviews/plan0017/live-manual-validation.md).

## 2026-10-21 검증 범위 갱신

개인 계정의 실제 OAuth·선택 저장소 연결에 이어 조건 전환 자동 가져오기,
중지 중 원본 변경과 재개 반영, 라벨 충돌/not_planned, 설치 중지·복원의 접근 차단,
단일 Task 일반 전환·휴지통 복원을 확인했다. GitHub Swagger 28개 operation도 확인했다.
Webhook은 현재 활성이고 공개 receiver의 실제 202 전달을 확인했다.
앞선 비활성·미인증 설명은 과거 상태이며 최신 근거는
[2026-10-21 검증 기록](../reviews/plan0017/live-manual-validation.md#2026-10-21-실제-github-변경-검증)이다.

남은 실제 범위는 조직 승인·사용자 철회·다른 계정 교체(별도 계정 미제공),
설치 제거, 저장소 일괄 해제/복구, 공개 Webhook 수신 중단 복구 및 같은 DB의 Task 반영이다.
공개 receiver와 로컬 DB가 다르므로 수신 202와 로컬 Task 성공을 하나의 흐름으로 간주하지 않는다.
기존 모의 HTTP/통합 검증과 구분하며 PLAN은 active다.
