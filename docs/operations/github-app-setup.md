# GitHub App test setup

PLAN-0017 implementation is in progress. This document describes setup, not a completed live integration.

## Register

Settings → Developer settings → GitHub Apps → New GitHub App. Use a separate test App.
Keep user authorization token expiration enabled. Device Flow is not required.
The application starts its own state-bound user authorization flow; do not depend on an unsolicited install callback to log in or attach repositories.

| Setting | Value |
| --- | --- |
| Callback URL | `https://<backend>/api/v2/integrations/github/callback` |
| Webhook URL | `https://<public-backend>/api/integrations/github/webhooks` |
| Repository Issues | Read-only |
| Metadata | Default read-only |
| Issue/Label events | Subscribe when Webhook is enabled |
| Other repository write permissions | None |

Installation/account lifecycle notifications must also be handled. App registration may deliver some lifecycle events without a selectable subscription checkbox.
If no public HTTPS receiver exists yet, leave Webhook inactive during preparation and enable it before live validation.
Use Only on this account for owner-only tests; use Any account if installing on another account/organization. Organization approval may be required.

## Credentials

Record App ID, Client ID and App slug from App settings. Generate a Client secret for the server-side user authorization flow.
In Credentials → Key pairs → New key, download the PEM private key (older UI: Private keys → Generate a private key).
Keep the PEM outside the repository; the server will read an explicitly configured file path. Do not paste its contents into chat, documentation or commits.
New key automatically downloads the PEM. The displayed SHA256 fingerprint is not a client secret or private key.
Generate the Client secret separately under Client secrets, and put the PEM file path in `GITHUB_PRIVATE_KEY_PATH`.
Generate a random Webhook secret locally and set the same value in GitHub and server configuration.
The token encryption key is a separate local deployment secret; do not reuse the Webhook secret or App private key for it.

No manually copied PAT is needed. The server obtains installation access tokens using the private key and obtains user access/refresh tokens through user authorization.
Install the App on selected test repositories only. Prepare a second test identity/organization role for access-revocation tests if available.

## Validation prerequisites

- Browser already has a service session before starting GitHub authorization.
- Callback host matches the service session cookie host; changing host can lose the session binding.
- GitHub can reach the Webhook HTTPS endpoint. A local-only URL is insufficient for GitHub delivery.
- Server key file, Client secret and Webhook secret are configured privately.
- Registration permissions and installed permissions match; changed permissions may require installation approval.

## Local authorization without a frontend

HTTP is allowed only for exact loopback hosts (`localhost`, `127.0.0.1`, `[::1]`) in browser redirect configuration. Other hosts require HTTPS.
Use `GITHUB_CALLBACK_URL=http://localhost:8080/api/v2/integrations/github/callback` and register the same URL with the App.
Use `GITHUB_FRONTEND_RETURN_URL=http://localhost:8080/github/connection-result` for the authenticated temporary result page.
Log into the backend on the same host first; use `SESSION_COOKIE_SECURE=false` for local HTTP and keep the configured `APP_ORIGIN` consistent.
The result page reports the actual connection state and contains no token, account name or repository data.
The public Webhook URL may point to a different deployment, but its events will be processed by that deployment, not this local process.
Keep Webhook inactive until the receiving implementation is deployed and its secret matches the App configuration.

Sources: [registration](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app), [private keys](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/managing-private-keys-for-github-apps).

## Registration check on 2026-10-19

The supplied PEM authenticated successfully against GitHub's read-only App API. App ID and
Client ID matched, Issues permission was read, and one active installation was visible.
The local `GITHUB_APP_SLUG` differed from the authenticated registration and was corrected.
GitHub reported an empty event subscription list. Remote settings, Webhook Active and
`GITHUB_ENABLED=false` were not changed. Before live delivery testing, configure the receiver
and subscriptions, then explicitly enable Webhook delivery. Browser user OAuth/client-secret
exchange and real Issue-to-Task delivery remain unverified.

## 2026-10-19 수동 검증 진행 갱신

사용자가 로컬 Swagger에서 OAuth, 설치/저장소 조회, 프로젝트 연결, Issue 가져오기,
수동 동기화에 의한 닫기/재개방 및 세 상태 라벨 매핑을 검증했다.
이 기록은 앞선 사용자 OAuth/Issue 동기화 미검증 설명을 갱신한다.
Webhook은 비활성으로 유지했다. 자동 가져오기 설정 저장은 응답 미확인이다.
남은 실제 검증은 사용자가 추후 진행하기로 했으므로 전체 완료 항목은 체크하지 않는다.
근거와 재개 절차: [수동 검증 기록](../reviews/plan0017/live-manual-validation.md).
