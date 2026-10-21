---
title: Phase 1 demo script
status: in_progress
owner: dell26/agent-5
deps: [web-capture,web-inbox,web-next-today,web-projects,web-task-detail,web-gtd-lists,web-trash,web-search,web-reminders,web-settings-gtd,p0-smoke]
touches: [web/e2e/phase1]
prio: 1
lease_until: 2026-10-21T04:25:17Z
extended: 
releases: 1
---
## Goal
Automate SPEC.md §13.2 Phase 1 end to end.

## Acceptance criteria
- [ ] `web/e2e/phase1/*.spec.ts` covers demo steps 1–9 (incl. tickler with the e2e clock and recurrence) and passes, together with phase 0 specs
- [ ] Defects found are fixed here or recorded as new tasks

## Notes
- ASSUMPTION (2026-10-21 04:39+0200 by dell26/agent-4): SPEC.md §13.2 phase 1 step 2 says 'Plan holiday' goes to a new project 'Holiday', but step 1 captures it with '#Holiday', which the parser only resolves for existing projects. The demo creates 'Holiday' in a set-up step and moves 'Plan holiday' to it; the 'New project' path of inbox processing is exercised end to end on a separate item instead.
- extended by dell26/agent-4: Done: web/e2e/phase1/demo.spec.ts covers SPEC §13.2 steps 1-9 (tickler with e2e clock + recurrence, inbox 'New project' path); inbox 'To project' fix; server CSRF fix (no SessionManagementFilter rotating XSRF-TOKEN on every authenticated request, which gave concurrent SPA requests 403) committed with test; check.sh passes. Left: run phase0+phase1 Playwright against the stack with the CSRF fix, re-run test-writer + code-reviewer (server change makes earlier reviews stale; it is outside touches, note it), quality.json, verify #1/#2, done.
- ASSUMPTION (2026-10-21 05:29+0200 by dell26/agent-4): web/e2e/gtd-inbox/inbox.spec.ts (web-inbox) failed 2 of 2 full runs: it counts the whole shared inbox while parallel specs (capture, lists, ...) add items. Fixed by a Playwright project 'inbox-exclusive' that runs gtd-inbox specs after all other specs (web/playwright.config.ts, outside touches), not by rewriting the spec.
- release by dell26/agent-4: Done (commits up to 3caa7480): web/e2e/phase1/demo.spec.ts covers SPEC §13.2 steps 1-9 (tickler w/ e2e clock + recurrence, inbox 'New project' path); fixes: inbox 'To project' (876d96fd), server CSRF token no longer rotated per authenticated request (c39fb343) + tests in CsrfCookieWebTests.kt (3caa7480, test-writer), inbox e2e runs in 'inbox-exclusive' Playwright project (10103031). review.md is fresh for the CSRF code (0 findings, rev 3e0db7c4). check.sh: server+web passed at 3caa7480, android was still running. Left: (1) run phase0+phase1 Playwright against the stack (npm run e2e -- --project=phase-demos, see web/e2e/README.md) and verify #1; (2) re-run test-writer so tests.md covers the CSRF change + new CsrfCookieWebTests tests (tests.md predates c39fb343), and code-reviewer only if the test file change is judged code under QUALITY.md Freshness; (3) verify #2 (defects fixed: inbox To-project, CSRF, inbox spec isolation); (4) write quality.json, commit, done.
