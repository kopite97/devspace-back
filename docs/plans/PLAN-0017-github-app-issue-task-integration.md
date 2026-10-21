# PLAN-0017: GitHub App and Issue–Task Integration

Status: `active`
Date: 2026-10-19

## Goal

프로젝트에 여러 GitHub 저장소를 연결하고, 사용자가 선택하거나 자동 가져오기 조건에 일치하는 Issue를 Task로 관리한다. GitHub Issue의 제목·본문·상태·라벨 변경을 단방향으로 반영하며 기존 Task 보드와 조회 기능을 활용한다.

이 문서는 [Runtime Template](PLAN-TEMPLATE-RUNTIME.md)을 따른다. PLAN 작성은 실행 승인이 아니다. 승인 전 구현, GitHub App 등록, 운영 설정 및 배포를 진행하지 않는다. 모든 실행 항목은 미완료 상태로 시작한다.

## Scope

- GitHub.com 개인·조직의 public/private 저장소, GitHub 계정 연결과 App 설치 선택, 프로젝트별 다중 저장소 연결·해제.
- Issue 목록·필터·페이지네이션·연결 상태 조회, Issue에서 새 Task 생성, 조건 충족 시점 기반 자동 가져오기. 기존 일반 Task에 Issue를 연결하는 기능은 제외한다.
- 저장소 연결별 동기화 일시 중지·재개, 사용자당 GitHub 계정 하나를 지원한다.
- 저장소 연결별 상태 라벨 매핑, GitHub → Task 단방향 동기화, 충돌·미진행 종료·권한 상실 표시.
- 최초 동기화, Webhook, 영속 작업·재시도·누락 복구, 수동 재동기화, 세션·소유권·외부 권한 검증.
- 기존 Task API·보드·통계·위젯의 연결 정보 및 접근 정책 정합화. GitHub 전용 위젯은 추가하지 않는다.
- 개발 완료 후 `docs/requirements/frontend/` 디렉터리를 새로 만들고 실제 구현 기준 프론트엔드 안내 Markdown을 작성한다. 프론트엔드 코드 구현은 제외한다.
- 개발일지·문서·첨부 연동, 마일스톤 연동, PR/Commit 참조 및 전용 조회, Branch/Actions/Release/배포 기능, GitHub Projects Status, GitHub Enterprise Server, SSE는 제외한다.
- GitHub Issue 생성·수정·닫기, 라벨 생성·변경, Task 드래그의 GitHub 반영 등 원격 업무 데이터 쓰기는 제외한다. 인증·토큰 발급·Webhook 전달 복구용 호출과 구분한다.
- 신규 프레임워크·메시지 브로커·Redis·외부 서비스는 전제하지 않는다. 기존 Spring/JPA/PostgreSQL과 Port/Adapter 경계를 사용한다. 추가 의존성이 필요하면 별도 승인받는다.
- 운영 배포, 실제 사용자 데이터 마이그레이션 실행, 운영 App 생성·권한 변경은 실행 범위에 자동 포함되지 않는다.
- 사용자는 이번 개발에서 기존 데이터 폐기를 허용했다. 기존 Task를 Issue에 연결하거나 연결 전 내용을 백업·복원하는 호환 기능은 만들지 않는다. 이 PLAN 수정 작업에서는 실제 데이터를 삭제하지 않는다. 구현 중 초기화가 필요하면 대상 환경·DB와 삭제 범위를 실행 기록에 명시하고, migration 파일의 기존 이력을 수정하는 수단으로 사용하지 않는다.

## Repository Review

- 확인 지침: 루트 AGENTS, `docs/agents/` 지침, Plan Guide, Runtime Template, Decision Guide. 구현 시 변경 대상 지침과 실제 기준선을 다시 확인한다.
- `Project.repositoryUrl`은 단일 문자열이며 인증된 저장소 연결이 아니다. 기존 필드를 유지하고 프로젝트–저장소 관계를 별도로 도입한다.
- `Task`는 workspace/project에 속하고 상태는 `todo/doing/done`, priority는 `normal/high`, tag는 단일 문자열이다. soft delete/restore, revision 및 생성 재실행 계약이 있다.
- `TaskCommandService`는 workspace → 정렬된 project → task 순으로 잠근다. 프로젝트 이동과 archived 정책이 존재하므로 연동 동시성에도 이 순서를 보존한다.
- Task 제목 160자, 설명 10,000자 제한은 GitHub 원문과 다를 수 있다. 원문을 기존 필드에 무조건 주입할 수 없다.
- 실제 위젯 확장 기반은 독립 `Widget`와 `HomeLayout`이다. [DECISION-0004](../decisions/DECISION-0004-independent-widget-and-dashboard-placement.md)를 따른다. 구 HomeDashboard JSON 배열 확장을 전제로 하지 않는다.
- [기존 검토 문서](../requirements/github-integration-and-widgets.md)는 광범위한 후보와 과거 대시보드 구조를 포함한다. 이번 범위는 이 PLAN이며 기존 문서 전체를 구현 요구로 취급하지 않는다.
- 장기 결정인 인증 주체·동기화 책임·연결 모델은 Session 1에서 필요한 proposed 결정으로 분리한다. 결정의 accepted 전환과 지침 변경은 명시적 승인 절차를 따른다. 이 PLAN으로 `docs/agents/`를 변경하지 않는다.

## Proposed Contract

### 연결과 권한

- 기존 서비스 세션 로그인과 GitHub 외부 계정 연결을 분리한다. 일회성·만료 가능한 state로 현재 사용자와 callback을 결합하고 설치 ID만으로 연결을 승인하지 않는다.
- 서비스 사용자당 활성 GitHub 계정은 하나만 허용하고 GitHub user ID로 식별한다. 같은 계정 재인증은 기존 관계를 유지한다. 다른 계정의 callback은 기존 연결을 자동 덮어쓰지 않는다. 계정 교체는 영향 프로젝트·저장소를 안내한 후 기존 연결 해제/보호 데이터 정리를 완료하고 수행한다. 한 계정으로 여러 설치·조직 저장소를 선택할 수 있다.
- App이 접근 가능한 범위와 현재 GitHub 사용자의 저장소 접근 권한을 함께 검증한다. App installation token은 백그라운드 조회용이며 사용자 권한의 대체물이 아니다.
- App 권한은 Issue 읽기와 연결 검증에 필요한 최소 권한으로 제한한다. 설치·저장소 변경 및 Issue/라벨 관련 이벤트를 필요한 범위만 구독한다. 정확한 endpoint·권한·이벤트 표는 Session 1에서 공식 문서와 대조한다.
- user token 갱신·철회, installation token 만료·재발급, 비밀키 교체를 다룬다. 지속 보관하는 비밀은 암호화하고 키를 DB와 분리한다. 응답·로그에 토큰을 노출하지 않는다.
- 인증 수단을 구분한다. App JWT는 installation token 발급과 App Webhook 전달 조회/재전송에, installation token은 설치 범위의 수집에, user access token은 사용자 접근 검증에 사용한다. App JWT는 기존 서비스의 세션 인증을 대체하지 않는다.
- 사용자별 token 갱신은 다중 인스턴스에서도 하나만 수행하도록 영속 claim과 token version으로 조정한다. 외부 갱신 호출 중 DB transaction은 유지하지 않으며 교체 시 버전을 재확인한다. 구 token을 사용한 요청 실패는 최신 token version을 먼저 확인하고 곧바로 계정 철회로 판정하지 않는다.
- refresh 성공 후 응답 유실/DB 저장 전 종료 시 이미 소비된 refresh token을 무한 재시도하지 않는다. 새 자격증명을 복구할 수 없으면 `reauthorization_required`로 전환하고 연결 데이터를 보호한 채 사용자 재인증으로 복구한다. 원격 token 교체와 로컬 저장의 원자성을 보장한다고 주장하지 않는다.
- 개인/조직 설치, 조직 승인 대기, 설치 중지·삭제, 선택 저장소 제거, 사용자 접근 철회, 계정 연결 해제를 구분한다. 권한을 확인할 수 없으면 private 내용 조회를 차단한다. 사용자별 검증 수명과 재검증 시점을 Session 1의 출시 조건으로 확정한다.
- 프로젝트당 0개 이상의 저장소 연결을 허용한다. 같은 프로젝트/저장소는 유일하며 같은 저장소를 다른 프로젝트에 연결할 수 있다. GitHub numeric ID와 앱 내부 연결 ID를 구분한다.
- workspace 간 데이터·작업·권한은 격리한다. 공유 설치나 동일 저장소라는 이유로 다른 사용자의 접근을 허용하지 않는다.
- private Issue에서 복사한 Task 제목·본문 역시 보호 대상이다. Task 상세/목록/검색/통계/overview/보드/위젯/휴지통/재실행 응답까지 우회 노출을 점검하고 접근 불가 시 가림·제외 정책을 일관되게 적용한다. 외부 권한 확인 동안 DB transaction을 유지하지 않는다.
- 권한 확인은 비트랜잭션 조정 단계에서 수행하고, 결과를 사용자·계정 연결 세대·installation·repository·검증 시각·만료 시각에 묶어 저장한다. 이후 짧은 DB 조회 transaction에서 현재 연결 세대와 유효한 접근 범위를 확인한다. 기존 TaskQueryService의 transaction 안에서 GitHub를 호출하지 않는다. 한 요청에서 같은 저장소의 검증을 Task별로 반복하지 않는다.
- 목록/검색/total/상태별 집계는 동일한 접근 조건을 DB 쿼리에 적용한다. 페이지를 읽은 뒤 Java에서 숨긴 항목을 제거하지 않는다. 접근 불가 연결 Task는 이 결과들에서 제외하고 상세/재실행 응답은 내용을 반환하지 않는다. 일반 Task와 명시적으로 전환 완료된 Task는 기존 소유권 규칙을 따른다.
- 접근 범위 변경을 감지할 수 있는 버전을 두고 cursor에 결합한다. 범위가 바뀐 cursor는 재조회가 필요한 오류로 처리한다. 한 응답의 목록·개수·관찰 메타데이터는 같은 DB snapshot을 사용한다. GitHub 접근 장애는 일반 Task 조회까지 전부 실패시키지 않으며, 외부 연결 제외/확인 대기 상태를 별도 메타데이터로 알린다.
- 캐시가 유효한 기간에는 철회를 아직 모를 수 있다. 수신한 철회 이벤트는 즉시 검증 결과를 무효화하고, 만료 후 재검증 실패/불명확 상태에는 접근을 차단한다. 검증 유효기간과 요청 시간 제한은 Session 1에서 호출량을 고려해 출시 전 고정한다. 이는 허용되는 관측 지연이며 GitHub 권한 철회 순간의 무지연 차단을 보장하지 않는다.

### Issue 조회와 가져오기

- 연결된 저장소별 open/closed, 라벨, 담당자 필터와 페이지네이션을 제공한다. PR은 Issues API의 `pull_request` 식별자로 제외한다.
- 초기에는 열린 Issue를 기본 미리보기로 제공하고 사용자가 선택해 가져온다. 닫힌 Issue도 명시적으로 선택할 수 있다. 저장소 연결만으로 과거 Issue 전체를 Task로 생성하지 않는다.
- Task 하나에는 상태 원본 Issue 하나만 연결한다. 동일 프로젝트/Issue에 Task 하나만 허용하며 동시 수동/자동 가져오기를 DB 제약과 재실행 계약으로 중복 방지한다.
- 가져오기는 새 Task 생성만 지원한다. 기존 일반 Task에 원본 Issue를 지정하거나 일반 Task로 전환한 항목을 자동 재연결하는 API는 제공하지 않는다.
- 자동 가져오기는 연결별 기본 off이며 포함 라벨·담당자 조건을 지원한다. 라벨 목록의 AND/OR, 담당자와의 결합, 빈 조건 의미를 Session 1에서 고정하고 미리보기에 동일 판정을 사용한다.
- 자동 가져오기는 Issue 생성일과 무관하게 활성화 이후 조건을 새로 충족하는 시점에 수행한다. 기존 Issue에 대상 라벨/담당자가 추가돼도 대상이다. 현재 조건 충족 여부를 기준으로 처리하며 작업 시점에 이미 조건을 벗어난 항목은 생성하지 않는다. 모든 순간적인 조건 변화를 빠짐없이 재현한다고 약속하지 않는다.
- 최초 활성화·off 후 재활성화·조건 변경 시 현재 일치 항목을 미리보기로 보여주고 선택한 항목만 가져온다. 나머지는 해당 설정 세대의 기준 상태로 기록해 무관한 수정 이벤트만으로 소급 생성하지 않는다. 이후 비일치 → 일치 전환은 자동 대상이다. 초기 대조와 이벤트 처리 사이의 누락을 방지하고 조건 충족 시점은 GitHub의 이력 전체가 아니라 관측·대조 기준임을 설명한다.
- 자동 가져오기 off는 이미 가져온 Task의 동기화를 중단하지 않는다. 삭제·일반 Task 전환 등 사용자 제외 기록은 설정 변경/재활성화/재개보다 우선하며 자동 생성하지 않는다. 일시 중지 중 조건 변화는 재개 시 별도 후보 미리보기로 처리한다.
- 조건/매핑 변경은 기존 Task를 삭제하지 않는다. 가져오기 조건을 벗어나거나 대상 라벨이 제거돼도 이미 연결된 Task의 동기화는 유지한다.
- 신규 Task의 내부 priority/tag 기본값은 기존 일반 Task 계약에 맞춰 확정한다. GitHub 여러 라벨을 단일 tag 필드에 합치지 않는다.
- 기존 Task 제목 160자/설명 10,000자(기존 UTF-16 측정 기준) 제한을 유지하고 null 본문은 빈 문자열로 정규화한다. 최초 가져오기 시 초과 항목은 `CONTENT_LIMIT_EXCEEDED` 사유로 거부하며 다른 선택 항목의 결과와 구분한다. 자동 가져오기도 같은 기준으로 제외 결과를 남긴다. 조용히 잘라 저장하거나 일반 Task 제한을 늘리지 않는다. 미리보기와 항목별 결과에서 사유·원문 이동·실패 항목 재시도를 제공한다.
- 연결 후 원문이 제한을 초과하면 마지막 정상 제목·설명을 유지하고 내용 동기화 오류를 표시한다. 상태·라벨·권한 등 독립 필드는 계속 갱신한다. 원문 버전/내용 해시로 같은 실패의 반복 재시도를 막고, 새 원문 관찰 또는 명시적 재동기화에서 재평가한다. 정상 길이로 돌아오면 최신 내용을 반영하고 오류를 해제한다. 최초 연결 거부 항목도 새 원문 관찰 시 자동 가져오기 조건을 다시 평가할 수 있다.
- 일반 Task 전환 시 마지막 정상 로컬 내용을 보존한다는 사실과 내용 동기화 오류를 미리 보여준다. 원문 전체 보관/축약 표시 모델은 이번 범위에 추가하지 않는다. Issue 조회용 원문에도 별도 응답 크기 상한을 두고 초과를 명시한다. 비신뢰 Markdown/HTML·이미지·URL은 프론트에서 안전하게 렌더링하도록 안내한다.

### 상태 라벨 매핑

매핑은 프로젝트의 저장소 연결별로 저장하며 실제 GitHub 라벨 ID를 선택한다. 기본 추천 이름은 Backlog/InProgress/Done이다. 하나의 라벨을 여러 상태에 매핑하지 않는다. 일반 분류 라벨은 상태 판정에서 제외한다.

| 원본 상태 | Task 처리 |
| --- | --- |
| open + Backlog | todo |
| open + InProgress | doing |
| open + Done | done |
| open + 매핑 라벨 없음 | todo |
| closed + completed | 라벨보다 우선하여 done |
| closed + not_planned | 기존 상태 유지, 미진행 종료 표시 |
| closed + 종료 사유 없음/미지원 값 | 기존 상태 유지, 확인 필요 표시 |
| open + 복수 상태 라벨 | 기존 상태 유지, 매핑 충돌 표시 |
| 재개방 | 다른 open Issue와 동일하게 현재 라벨로 재계산. Done이면 done |
| 매핑된 라벨 삭제 | 설정 오류 표시. 해당 라벨에 의존해 판정이 불가능한 항목만 마지막 정상 상태 유지 |

- 기존 상태가 없는 신규 가져오기의 충돌/미진행 종료/확인 필요 항목은 todo로 시작하고 사유를 표시한다. 기존 통계는 접근 가능한 Task의 보드 상태별 개수이며 완료 성과 지표가 아니다. not_planned가 기존 done을 유지하면 done 개수에도 포함된다. 미진행 종료 배지는 별도로 표시하고 이를 사유로 목록/집계에서 임의 제외하지 않는다. 취소를 제외한 성과 통계는 이번 범위에 추가하지 않는다.
- 유효한 상태 판정은 현재 Issue 상태·라벨·매핑으로 계산하며 재개방 이벤트 수신 이력을 판정 입력으로 사용하지 않는다. 따라서 동일한 현재 원본과 매핑의 재조회/재연결은 같은 판정을 만든다. 예외 표의 '기존 상태 유지'는 판정 불가 시 마지막 정상 값을 보존하는 동작이며 기존 값이 없는 경우 todo를 사용한다. 관측된 재개방을 참고 정보로 표시할 수 있지만 완전한 이벤트 이력을 약속하지 않는다.
- 라벨 이름 변경 시 ID 기반 연결을 유지한다. 매핑 변경은 영향 미리보기와 설정 revision을 사용하고, 적용 후 비동기 재계산한다. 오래된 매핑의 작업 결과를 새 설정에 적용하지 않는다.
- 충돌·취소·동기화 오류는 GitHub 연결 메타데이터이며 기존 세 가지 Task 상태 enum을 확장하지 않는다.
- Done 라벨 매핑은 선택 사항이다. 미지정 시 completed 종료로 완료를 표현한다. 라벨 없음 → todo 기본값을 설정 화면에 명시한다. 라벨 삭제 후에도 completed 종료나 다른 유효 상태 라벨로 판정 가능한 항목은 계속 갱신한다. 삭제와 일반 라벨 제거를 구별하고, 매핑 오류 해소 후 영향 항목을 재계산한다.
- 미진행 종료·충돌 배지와 필터를 제공하고 충돌 라벨 목록 및 GitHub에서 해결하는 경로를 응답한다. 사유 필터는 목록·total에 동일하게 적용하며 기존 상태별 집계 의미를 바꾸지 않는다. 동일 Issue를 여러 프로젝트로 가져오면 별도 Task로 집계되며 고유 Issue 수가 아님을 설명한다.

### Task 수명주기와 데이터 소유권

- 연결된 Task의 제목·설명·상태는 GitHub가 원본이다. 기존 PATCH/보드 경로에서도 해당 값의 직접 변경을 거부한다. priority/tag는 로컬에서 수정할 수 있다.
- 사용자에게는 'GitHub Issue를 프로젝트 보드에서 모아보기' 기능으로 안내한다. 카드에 'GitHub에서 관리', 저장소/Issue 번호, 수정 가능 필드, 마지막 반영 시각과 지연 상태를 제공한다. 프론트는 드래그/편집 전에 제한 이유를 보여주고 'GitHub에서 수정'으로 이동시킨다.
- 저장소 연결별 사용자 일시 중지는 Task 내용 갱신과 신규 수동/자동 가져오기를 중단하고 연결·기존 Task·설정을 보존한다. 권한 검증·철회 처리·Webhook 수신/저장소 조회 데이터 갱신은 계속한다. 중지 전 작업도 적용 단계에서 연결 세대를 확인해 Task를 변경하지 못하게 한다. 재동기화 요청이 일시 중지를 우회하지 않는다.
- 재개 시 권한을 재검증하고 기존 연결 Task를 최신 원본과 대조한 뒤 갱신한다. 중지 중 새로 조건을 만족한 미연결 Issue는 미리보기로 선택하게 하며 대량 자동 생성하지 않는다. 이후 자동 가져오기 기준 상태를 다시 설정한다. 자동 가져오기 off와 동기화 일시 중지는 별개 설정으로 안내한다.
- linked Task의 프로젝트 이동은 연결 해제 후에만 허용한다. 같은 저장소가 대상 프로젝트에도 연결됐다는 이유로 원본 관계를 자동 이동하지 않는다.
- 연결 해제는 현재 접근 권한을 확인한 사용자의 명시적 일반 Task 전환이다. 내용은 유지하되 원격 동기화를 중단하고 자동 재가져오기 제외 기록을 남긴다. 권한을 잃은 private 내용을 해제 경로로 복구·노출할 수 없어야 한다.
- 저장소 연결 해제는 영향 Task 수를 보여준 뒤 202/작업 ID로 처리한다. 짧은 transaction에서 `disconnecting` 전환과 연결 세대 증가로 신규 동기화/가져오기를 먼저 중단한다. 일반 Task 전환은 제한된 batch로 기존 잠금 순서에 따라 수행하고 진행률/실패 항목을 기록한다. 전체 Task를 단일 workspace 잠금 transaction에서 변환하지 않는다.
- batch 적용마다 유효한 접근 확인과 연결 세대·Task revision을 검증한다. 전환 완료 항목은 로컬 사본이며 부분 완료를 숨기지 않는다. 권한 상실 시 미전환 항목은 보호 상태로 남기고 작업을 중단한다. 재시도는 미완료 항목만 처리하며 정상 전환 또는 명시적 보호 데이터 정리가 끝나야 최종 해제 완료가 된다. 진행 중 일반 Task 전환/수정 충돌의 항목별 결과를 제공한다.
- 권한 상실 후에도 서비스 소유권만으로 연동 중지·로컬 자격증명 제거·보호 데이터 삭제를 요청할 수 있다. 이 정리 작업은 내용 반환·일반 Task 전환·원격 GitHub 삭제를 수행하지 않는다. Issue에서 생성된 보호 Task는 복구 불가능한 정리임을 명시적으로 확인받고 제거한다. 정리 대상별 결과와 제외 기록으로 자동 재생성을 막는다. 일반 Task 연결 기능은 없으므로 연결 전 로컬 내용을 placeholder로 대체하는 정책도 도입하지 않는다.
- Task soft delete는 동기화를 중지하고 자동 재생성을 막는다. restore는 기존 Task를 복구하되 연결/권한을 확인한 후 최신 원본을 재조회한다. 삭제 항목의 재가져오기는 중복 생성 대신 복원/명시적 연결 절차로 안내한다.
- archived 프로젝트에는 신규 연결·가져오기를 금지하고 자동 가져오기/Task 반영을 일시 정지한다. 기존 일반 Task의 archived 동작은 유지한다. 활성화 시 권한 재검증과 재개 절차를 적용하되 사용자가 별도로 일시 중지한 연결은 자동 재개하지 않는다. 사용자 중지와 프로젝트 보관 사유를 분리한다.
- Issue 삭제·이전·저장소 이름 변경·이전은 안정 ID와 원본 접근 검증으로 처리한다. 404만으로 삭제를 단정하지 않는다. 다른 저장소로 이전된 Issue를 승인 없이 새 저장소와 연결하지 않는다.
- 동기화가 Task 표시 값을 변경하면 기존 잠금·revision·workspace dataRevision 계약을 따른다. 같은 내용의 반복 이벤트는 불필요한 변경 번호를 만들지 않는다. 위젯 설정 revision과 layoutRevision은 변경하지 않는다.

### 동기화와 API 경계

- `github/{presentation,application,domain,infrastructure}` 기능 경계를 사용한다. GitHub 인증·HTTP·서명 검증은 adapter로 격리하고 Task 변경은 Task의 불변식을 보존하는 application 협력으로 수행한다. 순환 의존성을 만들지 않는다.
- 연결/라벨 매핑/Issue 조회 데이터/Task 연결/제외 기록/Webhook 수신함/동기화 작업을 책임별로 보관한다. 새로운 Flyway migration만 추가하며 적용된 migration은 변경하지 않는다.
- Webhook 원본 body의 서명을 검증하고 delivery ID로 수신을 식별한다. 이벤트를 DB에 영속 저장한 뒤 빠르게 2xx 응답한다. 저장 실패 시 성공 응답하지 않는다.
- Webhook 경로만 세션·CSRF·Origin 필터 예외로 좁혀 설정한다. 계정 callback은 state 검증을 적용하고 일반 브라우저 변경 API의 세션·CSRF·Origin 검증을 유지한다.
- 짧은 claim transaction → 외부 호출 → 짧은 적용 transaction으로 처리한다. lease·backoff·재시도 한도·실패 목록으로 재시작과 다중 인스턴스에 대응한다.
- 초기 수집 중 들어온 이벤트, 중복/역순 이벤트, 연결 해제 후 늦은 응답, 매핑 변경 중 재계산을 검증한다. 작업 세대와 현재 연결 상태를 적용 시 재확인한다.
- GitHub는 실패 Webhook을 자동 재전송하지 않는다. 제한된 전달 실패 점검·재전달과 장애 구간 대조, 사용자 수동 재동기화를 제공한다. 상시 저장소 전체 폴링은 하지 않는다. 점검 주기·조회 가능 이력 밖 복구·보관 기간은 Session 1에서 확정한다.
- 최초 수집 범위와 오래된 closed Issue의 추가 조회/보관 정책을 명시한다. 미수집 결과를 0건/전체 수집 완료로 표시하지 않는다.
- API rate limit/Retry-After, 설치별 요청량, 수동 재동기화 제한, 원본 데이터 크기·페이지 수·보관 한도를 설정한다. 사용자 입력 URL을 외부 요청 주소로 직접 사용하지 않는다.
- `lastSuccessfulSyncAt`, 수집 범위, 작업 상태, 권한 상태, 매핑 충돌을 구분한다. workspace revision은 GitHub 최신 상태의 증거가 아니다.

API 그룹 후보이며 정확한 경로·DTO·상태 코드·멱등성은 Session 1에서 구현 전에 고정한다.

| API 그룹 | 목적 |
| --- | --- |
| `/api/v2/integrations/github/...` | 계정 연결 시작/callback/해제, 접근 가능한 설치·저장소 |
| `/api/v2/projects/{projectId}/github/repositories` | 프로젝트 저장소 목록·연결·해제 |
| `.../{linkId}/issues`, `.../{linkId}/labels` | Issue/라벨 조회와 필터 |
| `.../{linkId}/task-imports`, `.../{linkId}/import-settings` | 미리보기·가져오기·자동 가져오기 설정 |
| `.../{linkId}/status-mapping` | 상태 매핑 미리보기·변경 |
| `.../{linkId}/sync-requests`, `.../{linkId}/sync-status` | 재동기화 요청·진행 상태 |
| `.../{linkId}/sync-settings` | 동기화 일시 중지·재개 및 중지 사유 |
| `/api/v2/tasks/{taskId}/github-issue` | 가져온 Task의 연결 정보·일반 Task 전환. 기존 Task 연결 생성은 없음 |
| `/api/integrations/github/webhooks` | GitHub 서명 검증 전용 수신 |

- Task 응답에 optional 연결 정보·원본 링크·동기화/충돌 상태·수정 가능 필드를 추가하는 방향으로 검토한다. 일반 Task의 필수 계약을 보존한다.
- 조회는 no-store와 기존 공통 오류 형식을 유지한다. 긴 작업은 202와 작업 ID, 개별 실패 결과를 제공하며 재요청이 중복 Task를 생성하지 않게 한다.
- 접근 불가 404, 인증/CSRF 401/403, revision·원본 관리 필드·중복 연결 등 409, 제한/일시 장애 상태를 구분한다. GitHub 내부 응답·비밀은 그대로 노출하지 않는다.
- 프론트가 '결과 없음'과 '일부 외부 결과 표시 불가'를 구분할 수 있도록 일시 확인 실패/재인증 필요/접근 상실/사용자 중지/프로젝트 보관을 구분한다. 각 상태에 다시 확인·재인증·권한 확인·재개 행동을 매핑하고 숨긴 Issue의 제목이나 상세를 안내 메시지에 포함하지 않는다.

## Review Constraints and External Validation

검토에서 제기된 7개 항목은 이번 PLAN의 계약·실행 순서·검증에 반영했다. 다음 보장은 이번 구현에서 제공하지 않거나 실제 환경 확인 전 확정할 수 없다.

관련 작업별 후속 검토와 외부 환경 의존 항목은 [docs/todo 목록](../todo/README.md)에 분리해 기록한다. TODO 기록은 이 PLAN의 필수 구현·검증을 범위에서 제외하는 근거가 아니다.

| 항목 | 이번 PLAN의 처리와 한계 |
| --- | --- |
| GitHub 권한 철회 순간의 무지연 차단 | GitHub와 로컬 DB 사이에 원자적 권한 확인은 없다. 유효기간 제한·이벤트 무효화·재검증 실패 차단을 구현하고 지연 한계를 공개한다. 이미 전달된 브라우저 내용이나 권한이 있을 때 명시적으로 전환한 로컬 사본을 원격 회수하지 않는다. |
| token 갱신과 DB 저장의 원자성 | 보장하지 않는다. 중복 갱신을 막고 복구 불가능한 결과 유실은 사용자 재인증으로 해결한다. |
| 모든 Task의 일괄 해제 원자성 | 포기하고 batch 진행률·부분 완료·재시도 계약으로 대체한다. 전환 완료 로컬 사본과 미전환 보호 항목을 구분한다. |
| 취소를 제외한 완료 성과 지표 | 후속 범위다. 이번 통계는 보드 상태별 개수이며 not_planned 사유를 별도 표시한다. |
| 긴 Issue 원문을 Task에 무손실 복제 | 이번 범위에서는 지원하지 않는다. 최초 거부·마지막 정상 내용 보존·명시적 오류로 처리한다. |
| 최적의 TTL·batch 크기·운영 처리량 | 코드만으로 최적값을 보장하지 않는다. Session 1에서 보수적 상한을 고정하고 격리 부하/호출량 검증으로 조정하며, 미확정 상태로 출시하지 않는다. |
| 실제 개인/조직·private·설치 승인·사용자 철회 | 격리된 GitHub App, 허용된 계정/저장소와 외부 HTTPS 환경이 필요하다. 환경을 확보하기 전에는 모의 검증만 가능하며 실제 검증 완료로 표시하지 않는다. |

근거: [GitHub 사용자 인증](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-with-a-github-app-on-behalf-of-a-user), [회전되는 refresh token](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens), [App JWT가 필요한 Webhook 전달 API](https://docs.github.com/en/rest/apps/webhooks). 구현 시 API 버전과 지원 범위를 다시 확인한다.

## Execution Sessions

세션을 순서대로 실행한다. 각 구현·검증이 끝나야 다음 세션으로 이동하며 실제 완료한 항목만 체크한다.

### Session 1: 계약과 외부 연동 기준 확정

Objective: 구현 전에 범위·보안·상태·수명주기와 API 계약을 검증 가능한 기준으로 고정한다.

Implementation:

- [x] 명시적 실행 승인 후 PLAN을 active로 변경한다. 2026-10-19 사용자 승인.
- [x] 현재 코드·DB migration·OpenAPI·Task/보드/위젯 조회 경로와 기존 테스트 기준선을 확인한다.
- [x] 장기 결정이 필요한 인증 주체·영속 동기화 책임을 DECISION-0007로 기록했다. 이미 승인된 PLAN 범위이며 새 아키텍처 승인을 요구하지 않는다.
- [x] 세부 운영값을 [backend contract](../requirements/github-issue-task-backend-contract.md)에 고정했다. TTL 60초, HTTP 3/10초, batch 50, 복구 10분 등은 검증 전 기준값이다.
- [x] 공식 GitHub 문서의 API 버전·token 유형·권한·Webhook 계약을 확인하고 근거를 backend contract에 기록했다.
- [x] endpoint/DTO/오류/멱등성/원본 관리 필드/revision/가림·통계 정책과 migration 설계를 backend contract에 작성했다. 구현 후 실제 OpenAPI와 재대조한다.
- [x] 격리된 App·시험 저장소·HTTPS callback/Webhook 환경 및 사용자/조직 테스트 가능 여부를 확인한다. 실제 자격증명이 없으면 모의 검증과 실제 검증의 경계를 기록한다.

Validation:

- [x] 일반 Task와 연동 Task의 모든 읽기/쓰기/복원/이동 경로에 정책 누락이 없는지 검토한다.
- [x] private 데이터가 Task 복사본·재실행 결과·집계로 우회 노출되지 않는 설계를 검토한다.
- [x] 필수 환경이 없는 상태를 완료로 간주하지 않고 후속 실제 연동 검증 조건을 기록한다.

### Session 2: 연결 모델과 GitHub 인증

Session 1 기록: 코드·migration V1–V21(별도 V16/V17 포함)·기존 Task/overview/widget 조회와 OpenAPI 테스트 기준선을 검토했다. 기존 baseline 326개 테스트가 모두 통과했다(`docs/reviews/plan0017/baseline-tests.json`). 사용자 테스트 저장소와 App은 준비 중이며 공개 Webhook 주소는 등록하되 비활성 상태다. 실제 연동 검증은 미완료로 유지하며 로컬 구현/모의 검증은 진행 가능하다.

진행 기록(2026-10-19): V22–V27, 세션/PKCE 인증, 암호화, refresh claim/version, 저장소 소유권·중복 방지, 접근 TTL/claim·설치 세대와 오래된 검증 응답 차단을 구현했다. localhost HTTP callback과 인증된 임시 완료 페이지를 추가했다. 사용자 별도 요청에 따라 암호화/Webhook secret 두 개를 로컬 `.env`에 생성했으며 출력·커밋하지 않았다. 설치 JWT·저장소 제한 token, HTTP 제한 및 PostgreSQL 연동 테스트를 진행한다. Webhook 실제 수신·Issue/Task 구현 및 실제 App 검증 완료를 의미하지 않는다.

Objective: 인증된 사용자만 자기 프로젝트에 허용된 저장소를 연결할 수 있다.

Implementation:

- [x] 계정/설치/저장소 연결과 비밀 보관 모델, 새 migration 및 필요한 설정을 추가한다.
- [x] 새 환경변수는 root `.env`와 `.env.example`에 반영하되 안전한 예시만 커밋한다. 실제 비밀을 출력·커밋하지 않는다. 별도 명시적 사용자 요청에 따른 로컬 키 생성만 수행했다.
- [x] state/callback 검증, token 발급·갱신, 설치·저장소 권한 검증과 연결 관리 API를 구현한다.
- [x] 비트랜잭션 권한 조정·검증 결과/접근 범위 버전과 token 갱신 claim/version, 복구 불가 시 재인증 상태를 구현한다.
- [x] 다중 저장소·중복 금지·조직 승인 대기·계정 연결 해제·설치 철회 동작을 구현한다.
- [x] 사용자당 GitHub 계정 하나의 제약, 동일 계정 재인증과 다른 계정 교체의 영향 안내·기존 관계 정리를 구현한다.

Validation:

- [x] state 재사용·만료·다른 사용자 callback, installation ID 변조, workspace 교차 접근을 거부한다.
- [x] 동시 계정 연결에서도 활성 계정이 하나이고 다른 계정 callback이 기존 설치·Task 관계를 덮어쓰지 않는지 검증한다.
- [x] 조직/public/private 및 token 갱신·권한 부족·설치 중지 시나리오를 검증한다.
- [x] 동시 갱신·갱신 응답 유실·저장 전 종료·구 token 요청 실패와 App JWT 전용 호출을 검증한다. TTL 만료·철회 무효화·권한 API 장애에서 private 접근이 열리지 않는지 확인한다.
- [x] 빈 DB와 기존 데이터가 있는 격리 DB의 migration/JPA validate 및 기존 repositoryUrl 보존을 검증한다.

검증 기록: GitHub 관련 20개 테스트(인증·DB 경합/권한·HTTP·암호화·설치 token·설정·V21→V27 데이터 보존)가 모두 통과했다. 조직 승인 대기는 승인된 설치만 사용자 API에 나타나는 모델이며, 설치 URL을 제공한다. 조직/public/private·설치 중지/철회는 모의 API/영속 무효화 검증 기준이다. 실제 App에서의 인증·설치 이벤트 검증은 Session 6에 미완료로 남긴다.

### Session 3: Issue 조회와 영속 동기화

Objective: Issue 상태를 최초 수집하고 변경·장애 후 복구할 수 있다.

Implementation:

- [x] Issue/라벨 adapter, 최초 수집·조회·페이지네이션·필터·수집 범위 표시를 구현한다.
- [x] 서명 검증 Webhook, 수신함, 작업 claim/lease, 재시도와 세대 검증을 구현한다.
- [x] 전달 누락 점검/재전달·대조, 수동 동기화, rate limit과 상태 조회를 구현한다.

Validation:

- [x] PR 제외, 빈 저장소/Issues 비활성/권한 상실, 오래된 데이터 추가 조회를 검증한다.
- [x] 위조 서명·중복·역순·초기 수집 중 변경·프로세스 종료·DB 저장 실패·늦은 응답을 검증한다.
- [x] Webhook 응답과 비동기 처리 분리, 외부 호출 중 DB transaction 미유지, 실패 전달 복구를 확인한다.

검증 기록(2026-10-19): GitHub 관련 30개 테스트 통과 후 redirect/HTTP 제한 관련 5개 테스트를 추가 대조했다. 서명·중복·DB 저장 실패, 영속 페이지 진행·lease 만료·구 작업 차단, 조회 필터·cursor 범위, App JWT 전달 복구 및 HTTP 본문 시간/크기 제한을 모의 API와 PostgreSQL로 검증했다. 수집은 한 페이지/claim으로 제한하며 큰 응답은 페이지 크기를 줄여 재시도한다. 오래된 닫힌 Issue는 번호 조회로 가져오고, 대조 시 기존 캐시의 미관측 항목은 개별 확인 작업을 예약한다. 실제 GitHub delivery/조직 환경 검증은 Session 6에 남긴다.

### Session 4: Issue–Task 연결과 상태 동기화

Objective: 선택/자동 가져오기로 새 Task를 생성하고 저장소별 매핑으로 관리한다. 기존 일반 Task 연결은 제공하지 않는다.

Implementation:

- [x] 새 Task 생성용 선택 미리보기/가져오기, 조건 충족 시점 기반 자동 가져오기·설정 세대별 기준 상태·제외 기록을 구현한다.
- [x] 라벨 매핑·영향 미리보기·재계산과 모든 예외 상태를 구현한다.
- [x] 제목/본문 원본 반영과 길이/Markdown 정책, 로컬 priority/tag 보존을 구현한다.
- [x] Task API의 직접 수정 제한·동시성·revision과 사용자에게 제공할 수정 가능 정보/충돌 사유를 반영한다.
- [x] private 내용을 처음 Task에 저장하는 시점부터 상세·목록·검색·통계·보드·overview·위젯·휴지통·재실행 경로의 접근 보호를 함께 적용한다. Session 5까지 보안을 미루지 않는다. 동일 접근 조건을 DB 조회/집계에 적용하고 범위 변경 cursor를 무효화한다.

Validation:

- [x] 상태 판정 표 전체, 동일 이름/다른 ID 라벨, 라벨 변경·삭제, 재개방 이벤트 유무와 무관한 판정, done → not_planned의 보드/통계 일치를 검증한다.
- [x] 길이 경계/초과/null 본문, 최초 거부·갱신 중 마지막 정상 내용 보존·정상 복귀·동일 원문 재시도 억제를 검증한다.
- [x] 접근 가능한 Task만으로 페이지·total·stats가 일치하고 권한 변경 후 cursor/재실행이 보호 내용을 반환하지 않음을 검증한다. 외부 확인 중 DB transaction을 유지하지 않는지 확인한다.
- [x] 동시 수동/자동 가져오기, 재요청, 여러 프로젝트의 같은 Issue를 검증한다. 오래된 Issue의 신규 조건 충족, 최초 활성화 기존 일치 항목, off/on·조건 변경 미리보기, 비일치 → 일치 전환, 제외 항목 재생성 방지를 검증한다.
- [x] 기존 일반 Task 연결 API가 없고 가져오기가 기존 제목·설명을 덮어쓰지 않는지 확인한다. 라벨 삭제가 무관한 항목의 갱신을 막지 않고 Done 매핑 없이 완료 처리가 가능한지 검증한다.
- [x] 로컬 우선순위 변경과 Webhook 경합에서 변경 유실이 없고 중복 이벤트가 revision을 불필요하게 올리지 않음을 확인한다.

### Session 5: 수명주기·권한 철회와 기존 기능 회귀

Objective: 삭제·이동·해제·외부 접근 철회 이후에도 데이터와 권한 계약을 지킨다.

Implementation:

- [x] Task 삭제/복원/일반 Task 전환, 저장소별 일시 중지·재개, 저장소 연결 해제, 프로젝트 archive/reactivate 정책을 구현한다.
- [x] Issue 삭제/이전, App 철회 및 사용자 접근 상실 시 늦은 작업을 차단하고 Session 4에서 적용한 읽기 보호를 수명주기 변경과 결합한다.
- [x] batch 연결 해제·부분 완료·재개·권한 상실 중단과 내용 비노출 정리/자격증명 제거를 구현한다. Task 상세·목록·검색·통계·보드·overview·위젯·휴지통·재실행 응답의 일관성을 회귀 점검한다.

Validation:

- [x] 삭제 Task 자동 재생성 방지와 명시적 복원, 이동 제한, 연결 해제/재연결 경합을 검증한다.
- [x] 일시 중지 중 Task 갱신·가져오기가 없고 권한 철회는 반영되는지 검증한다. 재개 시 기존 Task 대조·신규 후보 선택, 프로젝트 재활성화 후 사용자 중지 유지, 중지 이전 늦은 작업 차단을 확인한다.
- [x] private 접근 철회 후 모든 조회/집계/재실행/해제 경로의 정보 노출과 새 데이터 재삽입을 검증한다.
- [x] 일반 Task 및 프로젝트·위젯 기존 동작, 잠금 순서, layoutRevision 비변경을 회귀 검증한다.
- [x] 대량 해제 시 transaction/batch 상한과 다른 workspace 작업의 진행, 중간 종료 후 재시도, 부분 전환 후 권한 철회, 보호 데이터 정리의 비노출·자동 재생성 방지를 검증한다.

### Session 6: 통합 검증과 프론트엔드 안내 문서

Objective: 실제 구현을 검증하고 프론트엔드가 추측 없이 연동할 수 있는 안내 문서를 제공한다.

Implementation:

- [x] OpenAPI와 실제 요청/응답·오류·설정 기본값을 정합화하고 격리 환경에서 전체 연결 흐름을 검증한다.
- [x] backend 개발 및 검증이 끝난 후 `docs/requirements/frontend/`를 생성하고 `github-app-issue-task-integration.md`를 작성한다. 현재 PLAN 작성 단계에는 최종 구현 지침을 미리 생성하지 않는다.
- [x] 문서에는 실제 확정 경로/메서드/요청·응답 JSON/HTTP 상태·오류 코드/세션·CSRF·Origin/callback 복귀/페이지네이션/작업 상태/재시도·revision 계약을 포함한다.
- [x] 단일 계정 연결/교체·조직 승인 대기·저장소 선택·Issue에서 새 Task 가져오기·라벨 매핑·충돌 해결·조건 충족 시점 자동 가져오기·일시 중지/재개·연결 해제 화면 흐름과 버튼 활성 조건을 설명한다. 기존 Task 연결 화면은 포함하지 않는다.
- [x] GitHub 관리 카드의 드래그/편집 제한·내부 priority/tag 편집·원문 수정 버튼, 미진행 종료/충돌 필터, 조건 변경/재개 후보 미리보기, 내용 초과 항목의 실패 사유·재시도를 안내한다.
- [x] 조회 결과 없음과 일부 표시 불가, 재인증·접근 상실·동기화 지연·중지 사유를 구분하는 화면 상태/행동 표를 제공한다. GitHub 계정 해제·프로젝트 저장소 해제·Task 일반 전환·App 제거의 영향을 구분한다.
- [x] linked Task 수정 제한, 원본 표시, Markdown 안전 렌더링, 길이 초과, 권한 상실 가림, 휴지통·복원·프로젝트 이동, 미진행 종료와 통계 의미를 명시한다.
- [x] 권한 확인 유효기간과 관측 지연, 접근 제외에 따른 total/cursor 변경, token 재인증, 내용 제한 초과 후 마지막 정상 표시, 비동기 해제의 부분 완료/중단/비노출 정리 UI를 설명한다.
- [x] 브라우저 진입/포커스 복귀/수동 새로고침 시 backend 재조회, 비동기 작업 관찰과 캐시 무효화, dataRevision과 GitHub 최신성의 차이를 설명한다. Webhook만으로 열린 화면이 자동 갱신된다고 설명하지 않는다.
- [x] 정상/실패/충돌/권한 상실 예시와 프론트엔드 수용 테스트 목록, 지원하지 않는 기능, 실제 검증 한계를 포함한다. 비밀·실사용자 데이터·예정 API를 구현 완료 예시로 넣지 않는다.

Validation:

- [x] 프론트엔드 안내의 모든 endpoint·DTO·상태 값·예시를 실제 OpenAPI와 통합 테스트 결과에 대조한다.
- [x] 문서만으로 연결 → 가져오기 → 라벨 변경 반영 → 충돌 표시 → 연결 해제를 재현할 수 있는지 검토한다.
- [x] 실제 GitHub App 시험은 격리된 저장소에서 수행하고 사용한 권한·시나리오·결과를 기록한다. 불가능하면 사유와 모의 검증 범위를 명시하고 실제 연동 확인을 미해결 항목으로 남긴다.

### Sessions 4–6 실행 기록 (2026-10-19)

Session 4/5의 구현과 로컬 검증을 마쳤다. source 가시성 SQL view를 Task 상세·페이지·집계에
공유하고, 기준 상태·수집 버전·설정 revision·접근 generation으로 늦은 작업을 차단한다.
선택/자동 가져오기 경합, 다중 프로젝트의 같은 Issue, 내용 초과, 삭제 제외, 중지/재개,
archive/reactivate, 51개 원본의 50개 단위 해제·부분 중단·권한 상실 후 purge를 검증했다.

전체 `gradlew.bat build`의 365개 테스트가 통과했고, nullable OpenAPI와 추가 경계 사례를
반영한 집중 테스트 56개도 통과했다. 이후 `build -x test`로 최종 artifact를 재생성했다.
실제 로컬 HTTP의 서명 수신/변조 거부와 인증된 API의 MockMvc 검증을 구분해 기록했다.
상세 결과와 구현 계약의 구체화는 [검증 기록](../reviews/plan0017/implementation-validation.md)에 있다.

backend 개발/자동 검증 후 [프론트 구현 안내](../requirements/frontend/github-app-issue-task-integration.md)를
작성하고 실제 OpenAPI의 브라우저 27개 operation 및 Webhook, JSON 예제 6개와 대조했다.

실제 GitHub의 App JWT 인증·Issues read·활성 설치 1개는 확인했다. App slug 불일치를
로컬 `.env`에서 수정했으며 비밀은 기록하지 않았다. Webhook Active와 기능 비활성 설정은
사용자 선택대로 유지했다. 이벤트 구독 목록은 비어 있다. 수신 backend 배포·이벤트 설정·
명시적 활성화·서비스 세션에서의 GitHub 사용자 동의가 필요하므로 실제 OAuth/Issue 동기화/
공개 Webhook/조직 철회 검증은 미완료다. Swagger UI 자산/스키마는 자동 확인했지만
실제 브라우저 수동 렌더링 검증은 하지 않았다. 이 항목은 체크하지 않으며 PLAN은 `active`로 유지한다.
실환경 단계는 [기존 검증 TODO](../todo/github-live-integration-validation.md)에 정리했다.

## Final Validation

- [x] `./gradlew.bat test`와 `./gradlew.bat build` 또는 환경에 맞는 동등 명령을 실행하고 결과를 기록한다. 중복 실행은 필요한 경우에만 수행한다.
- [x] 상태 변환/필터 단위 테스트, PostgreSQL migration·제약·잠금·복원 통합 테스트, API 권한/CSRF/재실행 테스트가 통과한다.
- [x] GitHub HTTP 모의 서버로 token·pagination·rate limit·timeout·404·역순 결과·중복 전달·재시작·누락 복구를 검증한다. 이를 위해 새 테스트 의존성이 필요하면 먼저 승인받는다.
- [x] 격리된 실제 GitHub App에서 연결/선택 저장소/Issue 가져오기/라벨 변경/닫기/재개방/설치 철회를 확인한다. 조직 사용자 철회 등 검증하지 못한 경로는 별도로 남긴다. 설치 철회는 2026-10-21 일시 중지와 복원으로 검증했으며 제거는 미실행이다.
- [x] Task 및 기존 프로젝트/보드/위젯/overview API 회귀, N+1·페이지 제한·호출량·작업 backlog와 동기화 지연을 확인한다.
- [x] `docs/requirements/frontend/github-app-issue-task-integration.md`가 존재하고 최종 구현과 검증 결과에 일치한다.
- [x] No failures caused by this Plan remain.
- [ ] All Execution Sessions are complete.

### API Validation

- [x] Implemented endpoints are present in `/v3/api-docs`.
- [x] Request and response schemas match the approved backend contract.
- [x] Bean Validation constraints are reflected where applicable.
- [x] HTTP methods and response status codes match the contract.
- [x] Swagger UI renders the implemented endpoints correctly.
- [x] 실제 HTTP로 정상·권한 없음·중복·충돌·작업 대기·실패 응답을 검증한다.

## Completion

- 필요한 실행·검증 항목이 완료되고 미해결 blocker가 없을 때만 completed로 변경한다.
- 검증이 불가능하면 사유와 가장 강한 대체 검증을 기록한다. 모의 검증을 실제 GitHub 연동 성공으로 표시하지 않으며 미완료 항목은 체크하지 않는다.
- 프론트엔드 안내 Markdown 작성·계약 대조는 필수 완료 조건이다. backend 코드만 완료하고 문서 작성을 후속 작업으로 남기지 않는다.
- completed 전환 시 `docs/plans/README.md`에 순서대로 링크만 추가한다. proposed 단계에서는 완료 인덱스를 수정하지 않는다.
- 구현·승인 결정·검증 결과가 확정되면 PLAN의 실제 상태와 차이를 갱신한다. 운영 배포나 프론트엔드 구현까지 완료했다고 주장하지 않는다.

## 2026-10-19 수동 검증 진행 갱신

사용자가 로컬 Swagger에서 OAuth, 설치/저장소 조회, 프로젝트 연결, Issue 가져오기,
수동 동기화에 의한 닫기/재개방 및 세 상태 라벨 매핑을 검증했다.
이 기록은 앞선 사용자 OAuth/Issue 동기화 미검증 설명을 갱신한다.
Webhook은 비활성으로 유지했다. 자동 가져오기 설정 저장은 응답 미확인이다.
남은 실제 검증은 사용자가 추후 진행하기로 했으므로 전체 완료 항목은 체크하지 않는다.
근거와 재개 절차: [수동 검증 기록](../reviews/plan0017/live-manual-validation.md).

## 2026-10-20 배포 검증 재개

사용자 요청으로 검증을 재개했다. 개인 서버의 PEM 마운트 누락으로 인한 재시작을
Compose 읽기 전용 마운트와 UID/GID 10001 파일 권한으로 수정하고 같은 이미지로
app을 재생성했다. Compose 검증, PEM 읽기, Spring Boot 정상 시작을 확인했다.
프론트의 임시 결과 페이지 프록시가 없고, 공개 서비스 브라우저 접속은 사용자 거절로
차단되어 실제 연동 검증은 완료하지 못했다. Webhook 활성화 여부도 확인 대기다.
상세 근거는 [수동 검증 기록](../reviews/plan0017/live-manual-validation.md)에 남긴다.
상태는 active를 유지한다.

### 공개 브라우저 재확인 및 사용자 설명

2026-10-20 Chrome에서 공개 서비스 접근, Google 로그인, 인증된 GitHub 계정/저장소
조회에 성공했다. 공개 계정은 disconnected이며 현재 프로젝트의 연결 목록은 비어 있다.
배포 Swagger 비활성화와 프론트 GitHub 기능 미구현은 사용자가 확인한 의도된 현재 상태다.
프론트 구현이나 Swagger 공개를 이번 작업에 추가하지 않는다. 기존 로컬 검증과 공개 환경의
상태를 구분하며, 남은 실제 연동 검증과 PLAN 상태는 그대로 유지한다.
상세 결과는 [수동 검증 기록](../reviews/plan0017/live-manual-validation.md)을 따른다.

### 로컬 실제 HTTP 검증 재개

2026-10-20 기존 로컬 DB/서버를 기동하고 브라우저 로그인, GitHub connected 및 저장소
verified를 확인했다. Swagger에서 중지 200, 오래된 revision의 재개 409,
정상 재개 200과 baseline 완료, 자동 가져오기 조건 활성화 200과 baseline 완료를 확인했다.
기존 Task revision은 유지됐고 Issue #2가 새 Task로 생성되지 않았다.
검증 후 enabled=false/빈 조건/paused=false로 복원했으며 현재 설정 revision은 6이다.
비로그인 account API의 실제 HTTP 401도 확인했다.

GitHub 사이트 접근이 사용자 거절로 차단되어 원격 조건 전환과 Webhook 등은 검증하지
못했다. 우회하지 않았으며 전체 연동·전체 Swagger 렌더링 완료 항목은 체크하지 않는다.
소스 변경 없이 결과와 다음 시작점을 [수동 검증 기록](../reviews/plan0017/live-manual-validation.md)에
추가했다. PLAN은 active다.

### 2026-10-21 실제 변경·접근 차단 검증

사용자가 테스트 저장소 변경과 설치 일시 중지·복원을 승인했다. 조건 전환 자동 가져오기,
라벨 충돌, 중지 중 원본 수집 및 재개 반영, not_planned와 재개방, 재실행 멱등성 및
429 제한을 실제 GitHub와 로컬 API에서 확인했다. 설치 중지 시 보호 Task 상세/목록 및
동기화가 차단되고 복원 후 verified로 회복됐다. 신규 시험 Task의 일반 Task 전환과
휴지통 복원도 통과했다. Swagger GitHub 28개 operation을 펼쳐 응답 렌더링을 확인했다.

공개 Webhook의 실제 202 전달은 확인했으나 공개 DB의 Task 반영과 수신 중단 복구까지
검증한 것은 아니다. 조직 승인·사용자 철회·다른 계정 교체는 별도 시험 계정이 없어 남긴다.
설치 제거, 저장소 단위 일괄 해제/복구 역시 모의 검증과 구분한다. 전체 실행 완료는 체크하지
않고 active를 유지한다. 최종 설정 revision=12, 자동 가져오기 비활성, 빈 조건, paused=false다.
Issue #2는 open/라벨 없음, App 설치는 활성 상태로 복원했다. 상세 작업 ID와 잔여 상태는
[수동 검증 기록](../reviews/plan0017/live-manual-validation.md)을 따른다.
