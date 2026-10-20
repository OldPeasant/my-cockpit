---
title: Web setup and login pages
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,p0-auth-login]
touches: [web/src/app/features/auth,web/src/app/core/auth,web/src/app/app.routes.ts]
prio: 1
lease_until: 2026-10-20T12:14:23Z
---
## Goal
Setup wizard and login UI (SPEC.md §4.1).

## Acceptance criteria
- [x] Setup page (only reachable while setup is needed): username/password form, QR code of the otpauth URI, recovery codes with copy/download, TOTP confirmation
  - verified (2026-10-20 13:54+0200 by dell26/agent-2): Vitest setup.spec.ts + qr-code.spec.ts + auth.guards.spec.ts (setupGuard) in cd web && npm run check on 1d7dd9c -> pass: account form (12-char/match/username rules), QR SVG of otpauthUri, secret, 10 codes with copy (CDK Clipboard) and .txt download, TOTP confirm -> /login; /setup redirects to /login once setup is done. Not tried in a real browser against the server (no E2E named)
- [ ] Login page: password + TOTP, 'use a recovery code' switch, remember device; shows the backoff wait time on 429
- [ ] Auth guard on all shell routes; logout in the top bar menu; `/login` redirects to the return URL after success
- [ ] Vitest component/service tests cover setup and login forms, guard redirects; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-20 13:36+0200 by dell26/agent-2): QR code: added the qrcode npm library (+@types/qrcode), drawing its module matrix as an inline SVG path (no canvas, works under jsdom); allowed as CommonJS in angular.json
- ASSUMPTION (2026-10-20 13:36+0200 by dell26/agent-2): Retry-After: ApiError (core/http, outside touches) gained a retryAfter field parsed from the header, because the problem interceptor drops headers; login falls back to 60 s if a 429 has no header
- ASSUMPTION (2026-10-20 13:37+0200 by dell26/agent-2): Logout lives in a new account icon menu in the top bar (shell.html/shell.ts, outside touches), showing the username; a failed logout call still navigates to /login after a toast
- ASSUMPTION (2026-10-20 13:37+0200 by dell26/agent-2): Setup does not log in automatically: after TOTP confirmation it shows a toast and goes to /login, since the server's confirm-totp creates no session
- ASSUMPTION (2026-10-20 13:37+0200 by dell26/agent-2): Return URL: only same-app absolute paths are followed (not //host, scheme or /login|/setup); anything else falls back to the dashboard (open-redirect protection)
- ASSUMPTION (2026-10-20 13:37+0200 by dell26/agent-2): Auth state: setup status and /me are cached in AuthService; the login guard re-asks /me each time because a 401 elsewhere lands there while the cache still says logged in
