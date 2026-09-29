# Docker 배포

Java 21 JDK에서 Gradle Wrapper로 bootJar를 만들고 Java 21 JRE에서 일반 사용자로 실행한다.
`.env`, 로컬 빌드 결과, Git 이력, 테스트와 문서는 빌드 컨텍스트에 포함하지 않는다.
이미지 빌드는 DB나 OIDC 비밀값을 필요로 하지 않는다. 실행 시 환경변수를 주입한다.
Docker 빌드는 테스트를 실행하지 않으므로 코드 변경 검증은 별도로 수행한다.

## Render

1. Render Postgres를 생성하고 백엔드와 같은 계정·리전을 선택한다.
2. 저장소를 연결해 Web Service를 생성하고 Language를 **Docker**로 선택한다.
3. Root Directory는 이 백엔드 디렉터리, Dockerfile Path는 `./Dockerfile`, Docker Build Context는 `.`으로 설정한다. 저장소 루트가 백엔드이면 Root Directory는 비워 둔다.
4. Docker Command는 비워 두어 이미지의 기본 실행 명령을 사용한다.
5. Environment에서 아래 값을 설정한다. `Add from .env`로 가져오는 경우 로컬 주소와 DB 접속 정보를 교체하고, 아래의 일회성 Flyway 설정은 제외한다.
6. Health Check Path는 비워 두어 기본 TCP 확인을 사용한다. 현재 `/actuator/health`는 인증이 필요하므로 공개 HTTP health check로 지정하지 않는다.

| 변수 | 배포 값 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://<Render 내부 hostname>:5432/<database>?sslmode=require` |
| `DB_USERNAME` | Render Postgres 사용자 |
| `DB_PASSWORD` | Render Postgres 비밀번호 |
| `OIDC_GOOGLE_CLIENT_ID` | Google OAuth client ID |
| `OIDC_GOOGLE_CLIENT_SECRET` | Google OAuth client secret |
| `OIDC_GOOGLE_REDIRECT_URI` | `https://<공개 앱 도메인>/api/v1/auth/callback/google` |
| `APP_ORIGIN` | 브라우저에서 사용하는 앱 origin, 예: `https://app.example.com` (경로·끝 슬래시 제외) |
| `SESSION_COOKIE_SECURE` | `true` |
| `SESSION_COOKIE_NAME` | `__Host-devspace-session` |
| `PROJECT_CURSOR_SIGNING_KEY` | 최소 32 UTF-8 바이트의 충분히 무작위인 비밀값. 재배포 시 유지 |

애플리케이션은 기본적으로 모든 인터페이스의 8080 포트에 바인딩한다.
Render의 기본 `PORT`를 **8080**으로 변경하여 맞춘다.
다른 포트를 쓰려면 Render의 `PORT`와 Spring Boot의 `SERVER_PORT`를 같은 값으로 설정한다.
HTTPS 프록시 뒤의 요청 정보를 반영하려면 Render 환경에 Spring Boot 표준 설정인
`SERVER_FORWARD_HEADERS_STRATEGY=framework`를 설정한다. 개인 서버에서도 신뢰하는 프록시가
외부의 forwarded 헤더를 덮어쓰고 백엔드에 직접 접근할 수 없도록 구성한 경우에 사용한다.

선택값인 `OIDC_GOOGLE_ISSUER_URI`, `SESSION_IDLE_TIMEOUT`, `DEVSPACE_LINK_MAX_LINKS`는
필요하면 기존 값을 입력한다. 기본값은 `application.yml`을 따른다.
현재 로컬 `.env`의 `DB_URL`은 `${DB_HOST}`, `${DB_PORT}`, `${DB_NAME}`을 참조한다.
이 세 변수는 URL 조립에 간접적으로 사용된다. 문서의 완성된 `DB_URL`을 입력하면 세 변수는 필요 없다.
Render가 제공하는 `postgresql://user:password@host/database`는 JDBC URL이 아니므로 그대로 넣지 않는다.
비밀번호는 URL에 포함하지 않고 `DB_PASSWORD`로 전달한다. `.env` 파일 자체를 업로드할 필요는 없다.

### 현재 로컬 .env와의 대응

| 로컬 항목 | Render에서 처리 |
| --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_URL` | 위의 완성된 Render JDBC URL 하나로 지정. 로컬 주소를 유지하지 않는다 |
| `DB_USERNAME`, `DB_PASSWORD` | Render DB 계정으로 변경 |
| `OIDC_GOOGLE_CLIENT_ID`, `OIDC_GOOGLE_CLIENT_SECRET` | 같은 OAuth client를 쓰면 유지 가능. 배포 callback URI를 Google 콘솔에 추가 |
| `OIDC_GOOGLE_REDIRECT_URI`, `APP_ORIGIN` | localhost 주소를 실제 공개 HTTPS 주소로 변경 |
| `OIDC_GOOGLE_ISSUER_URI`, `SESSION_IDLE_TIMEOUT`, `DEVSPACE_LINK_MAX_LINKS` | 기존 값을 유지하거나 생략하여 application.yml 기본값 사용 |
| `SESSION_COOKIE_SECURE` | HTTPS 배포는 `true` |
| `SESSION_COOKIE_NAME` | 기존 이름도 가능. `__Host-devspace-session`은 HTTPS 운영 권장값 |
| `PROJECT_CURSOR_SIGNING_KEY` | 필수. 같은 서비스의 재배포·데이터 이전 시 유지 |
| `spring.flyway.init-sqls[0]` | 특정 기존 DB의 V16 전환용 일회성 SQL. 새 빈 Render DB에는 복사하지 않는다 |
| `PORT` | 로컬 .env에는 없는 Render 포트 설정. `8080` 지정 |
| `SERVER_PORT` | Spring Boot 표준 설정. 기본 8080을 쓰면 불필요 |
| `SERVER_FORWARD_HEADERS_STRATEGY` | Spring Boot 표준 프록시 설정. Render에서는 `framework` 지정 |

`spring.flyway.init-sqls[0]`는 로컬에서 `.env`를 Java properties로 읽기 때문에 적용되는 속성이다.
일반 환경변수와 구분해야 하며, 기존 DB의 ID·revision을 담은 manifest를 다른 DB에 재사용하면
V16 검증이 실패할 수 있다. 기존 데이터를 이전할 때는 전환 절차에 따라 별도로 검토한다.
로컬 `.env`는 로컬 개발용으로 유지하고 Render에는 배포용 값을 별도로 입력한다.

Google OAuth 콘솔에도 `OIDC_GOOGLE_REDIRECT_URI`와 정확히 같은 승인된 리디렉션 URI를 등록한다.
현재 인증은 세션 쿠키(`SameSite=Lax`), 상대 경로 로그인 리디렉션을 사용하며 CORS 허용 설정이 없다.
`APP_ORIGIN`만 바꿔서는 서로 다른 origin의 프론트/API 직접 호출이 가능해지지 않는다.
프론트와 API를 같은 공개 origin으로 제공하는 프록시 구성이 필요하다.
프록시는 `/api/**`, `/oauth2/**`, `/login/oauth2/**`를 백엔드로 전달하고 쿠키·리디렉션을 보존해야 한다.
독립 도메인 간 직접 호출을 원한다면 별도의 인증/CORS 설계 변경이 필요하다.
세션은 메모리에 있으므로 서비스 재시작 또는 재배포 시 다시 로그인해야 한다.

## 마이그레이션

새 DB는 기동 시 Flyway로 스키마가 생성된다. 기존 로컬 데이터는 자동 복사되지 않는다.
This source unconditionally produces final-schema artifacts; no stage build argument is used.
The build includes V1-V21 but does not execute migrations. Startup still requires
the reviewed V16 manifest where applicable and V17 replay-drain prerequisites. Follow the
[rollout procedure](project-category-only-rollout.md) before starting an image against an existing database.
For a database still in the bridge window, use the preserved pre-Widget artifact/source, not this image.
Legacy HTTP replay cannot be enabled by runtime configuration. PLAN-0014 verified local admission only;
Render remains uninspected and requires its own database, retention, writer and backup admission before release.

## 개인 서버로 이전

동일한 Dockerfile로 이미지를 만들고 실행 환경의 DB 주소만 교체할 수 있다.

```sh
docker build -t devspace-backend .
docker run -d --name devspace-backend --env-file .env -p 127.0.0.1:8080:8080 devspace-backend
```

`.env`는 `KEY=value` 형식이어야 하며 Docker `--env-file` 자체는 `${OTHER_VAR}`를 치환하지 않는다.
Spring은 설정을 읽을 때 중첩 placeholder를 해석할 수 있지만, 위 배포 예제에서는 혼동을 피하도록
완성된 JDBC 주소를 사용한다. 로컬의 일회성 Flyway 속성은 배포용 env 파일에 그대로 복사하지 않는다.
호스트에서 실행 중인 PostgreSQL에 Docker Desktop 컨테이너가 접속할 때는
`localhost` 대신 `host.docker.internal`을 사용한다.
Linux 개인 서버에서 DB도 Docker로 실행한다면 같은 Docker 네트워크에 연결하고
`jdbc:postgresql://db:5432/<database>`처럼 DB 서비스 이름으로 접속한다.
위 `docker run` 명령에도 해당 네트워크의 `--network <network>`를 추가한다.

개인 서버에서는 프론트/프록시, 백엔드, PostgreSQL을 각각 별도 컨테이너로 실행하고
Docker Compose로 관리할 수 있다. PostgreSQL 데이터는 영속 볼륨과 별도 백업으로 보관한다.
DB 데이터는 이미지에 포함되지 않으므로 Render DB를 `pg_dump`로 백업하고 개인 서버에서
`pg_restore`로 복원해야 한다. 최종 이전 시 쓰기를 중단하여 백업 이후 변경이 누락되지 않게 한다.
복원 대상 PostgreSQL 버전과 확장 기능의 호환성도 확인한다.

Render 무료 PostgreSQL은 생성 30일 후 만료되어 접근할 수 없고,
그 뒤 14일 유예 기간이 지나면 삭제된다. 만료 전에 백업·이전하거나 유료로 전환한다.

## 공식 문서

- [Render Docker](https://render.com/docs/docker)
- [환경변수와 .env 가져오기](https://render.com/docs/configure-environment-variables)
- [서비스 포트](https://render.com/docs/web-services#port-binding)
- [Health checks](https://render.com/docs/health-checks)
- [PostgreSQL 연결](https://render.com/docs/postgresql-creating-connecting)
- [무료 서비스 제한](https://render.com/docs/free)

## Document attachment storage

Set `FILE_STORAGE_ROOT` to a provisioned persistent directory and mount it with write access for UID/GID 10001. A regular `.devspace-document-storage` marker containing `devspace-document-storage-v1` is required. See [storage provisioning and recovery](operations/document-storage.md) for named volumes, bind mounts, backup/restore and delayed cleanup semantics. An ephemeral service filesystem is insufficient for document attachments.
