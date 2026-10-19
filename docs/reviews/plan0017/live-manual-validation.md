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
