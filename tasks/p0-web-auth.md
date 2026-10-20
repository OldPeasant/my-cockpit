---
title: Web setup and login pages
status: in_progress
owner: dell26/agent-2
deps: [p0-web-shell,p0-auth-login]
touches: [web/src/app/features/auth,web/src/app/core/auth,web/src/app/app.routes.ts]
prio: 1
lease_until: 2026-10-20T11:54:13Z
---
## Goal
Setup wizard and login UI (SPEC.md §4.1).

## Acceptance criteria
- [ ] Setup page (only reachable while setup is needed): username/password form, QR code of the otpauth URI, recovery codes with copy/download, TOTP confirmation
- [ ] Login page: password + TOTP, 'use a recovery code' switch, remember device; shows the backoff wait time on 429
- [ ] Auth guard on all shell routes; logout in the top bar menu; `/login` redirects to the return URL after success
- [ ] Vitest component/service tests cover setup and login forms, guard redirects; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-20 13:36+0200 by dell26/agent-2): QR code: added the qrcode npm library (+@types/qrcode), drawing its module matrix as an inline SVG path (no canvas, works under jsdom); allowed as CommonJS in angular.json
- ASSUMPTION (2026-10-20 13:36+0200 by dell26/agent-2): Retry-After: ApiError (core/http, outside touches) gained a retryAfter field parsed from the header, because the problem interceptor drops headers; login falls back to 60 s if a 429 has no header
- ASSUMPTION (2026-10-20 13:37+0200 by dell26/agent-2): Logout lives in a new account icon menu in the top bar (shell.html/shell.ts, outside touches), showing the username; a failed logout call still navigates to /login after a toast
