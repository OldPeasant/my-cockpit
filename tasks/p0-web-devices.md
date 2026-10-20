---
title: Settings: security and devices
status: in_progress
owner: dell26/agent-4
deps: [p0-web-settings,p0-auth-tokens]
touches: [web/src/app/features/settings/security]
prio: 2
lease_until: 2026-10-20T15:26:55Z
extended: dell26/agent-4
---
## Goal
Show and revoke Android devices; regenerate recovery codes.

## Acceptance criteria
- [x] Lists devices (name, last used) with revoke + confirm dialog
  - verified (2026-10-20 17:03+0200 by dell26/agent-4): cd web && npm run check (Vitest security-settings.spec.ts: lists devices with name + last use, revoke after confirm dialog, cancel/dismiss keeps device) -> pass; code review of 4ad721f confirms list + confirm dialog
- [x] Server: `POST /api/v1/auth/recovery-codes` (requires current TOTP) regenerates codes; UI shows them once
  - verified (2026-10-20 17:03+0200 by dell26/agent-4): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --rerun-tasks -> RecoveryCodeWebTests 11/11 (200 with current TOTP, wrong/missing TOTP 400, throttled 429); Vitest 'shows new recovery codes once' + 'does not keep the codes after the page is left' -> pass
- [x] Vitest component/service tests cover device list, revoke flow; `cd web && npm run check` passes
  - verified (2026-10-20 17:03+0200 by dell26/agent-4): sh scripts/check.sh on the task merged with main (includes cd web && npm run check) -> api up to date, lint clean, 24 test files passed, build OK, all checks passed
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*'` passes (Postgres via Testcontainers where persistence is involved) for the recovery code endpoint
  - verified (2026-10-20 17:03+0200 by dell26/agent-4): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*' --rerun-tasks -> BUILD SUCCESSFUL, 20 suites 0 failures/errors; RecoveryCodeWebTests (PostgresTestBase, Testcontainers) 11 tests pass

## Notes
- ASSUMPTION (2026-10-20 15:45+0200 by dell26/agent-4): POST /api/v1/auth/recovery-codes shares the global LoginThrottle with login (wrong TOTP counts as a failed attempt, 429 + Retry-After when blocked) so a stolen session cannot brute-force TOTP; wrong TOTP is a 400 with field 'totp', not 401, so the SPA does not treat it as a logout
- ASSUMPTION (2026-10-20 15:45+0200 by dell26/agent-4): Regenerating recovery codes deletes all old codes (used and unused) and returns 10 new ones; the UI holds them only in component state until 'I saved them' or leaving the page (copy button, no download)
- ASSUMPTION (2026-10-20 15:45+0200 by dell26/agent-4): Edited settings.routes.ts (outside touches) to replace the Security placeholder with the new section, as the routes file says section tasks do
- extended by dell26/agent-4: Implementation, tests (tests.md for final code), review.md, fixes.md (CR-1..CR-4) committed; sh scripts/check.sh passes. Left: re-run code-reviewer on final code (review.md predates fixes), run ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.*', record verify for #1-#4, write+commit quality.json, tasks.py done.
- ASSUMPTION (2026-10-20 17:04+0200 by dell26/agent-4) [confirmed 2026-10-20 17:05+0200 by Peter Gürtler]: Accepted the initial bundle warning (500.02 kB vs 500 kB warning budget, +19 bytes, warning not error; review rerun CR-1 P3): likely from Angular afterNextRender/viewChildren used for focus management; budget left unchanged so a human can decide whether to raise it
