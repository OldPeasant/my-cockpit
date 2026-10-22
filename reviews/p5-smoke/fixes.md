# Fix Report

Date: 2026-10-22

Task: p5-smoke - Phase 5 demo script

Scope: findings CR-1, CR-2 and CR-3 from reviews/p5-smoke/review.md, on the task range 769a79ea93bf64c0eb175f7323aed4c3d9dc2200..eee409f30 (uncommitted working-tree changes on top of eee409f30).

## Fixed findings

### CR-1 — Step 6 forces a mail sync the server already does after Send

Files changed:
- `web/e2e/phase5/demo.spec.ts`

Problem:
Step 6 called the e2e hook `POST /api/test/mail/sync` before it opened the cockpit's Sent folder. The comment said the cockpit only reads the Sent copy with its 5-minute folder poll. But `DraftService.send` appends the copy to Sent and resyncs that folder before it returns. So the hook was not needed, and it would hide a broken post-send resync.

Fix:
Removed the `syncMail` call and the now-unused `syncMail` helper. Corrected the comment: sending saves the copy to Sent and reads that folder right away. Step 6 now opens the reply from the cockpit's Sent folder without any hook. `APIRequestContext` is still imported because `apiOf` uses it. The parent already recorded a corrected step-6 assumption in tasks/p5-smoke.md.

Validation:
- `npm --prefix web run e2e:typecheck` — Pass
- `npm --prefix web run e2e -- --project=phase-demos` — Pass (step 6 passed without the hook)

### CR-2 — E2E_SMTP_PORT missing from docs/ARCHITECTURE.md

Files changed:
- `docs/ARCHITECTURE.md`

Problem:
The environment table did not list `E2E_SMTP_PORT`. The manual compose example pinned the IMAP port but published GreenMail SMTP on the compose default 18025.

Fix:
Added `E2E_SMTP_PORT` to the `npm run e2e` row of the environment table ("GreenMail IMAP/SMTP ports"). Added `E2E_SMTP_PORT=18093` to the manual compose example, next to `E2E_IMAP_PORT=18092`. web/e2e/README.md already documents `E2E_SMTP_PORT`, and its only compose command (recreating server-fresh/postgres-fresh) pins no ports, so it needed no change.

Validation:
- Checked by reading: `grep -n E2E_SMTP_PORT docs/ARCHITECTURE.md web/e2e/README.md` shows the table row, the example and the README row.
- `npx prettier --check ../docs/ARCHITECTURE.md` (from web/) reports a style warning. The file at HEAD gets the same warning before this edit (checked on a copy in .tmp/), and docs are not part of the project's lint or format check. Left as it is.

### CR-3 — Step 5 "Draft saved" check passes before any edit; saved draft text not checked

Files changed:
- `web/e2e/phase5/demo.spec.ts`

Problem:
The "Draft saved" status is already shown once the reply draft is created, so the assertion could pass on the status from before any edit. The Drafts check on GreenMail only looked at the attachment file name, not the typed text.

Fix:
Picking the file from Files saves the current content, including the typed text, together with the attachment (`pickFile` calls `storeContent`). After that, the spec now asserts the compose status element (`p.saved[role="status"]`) reads exactly "Draft saved <time>". A status of "Unsaved changes", "Saving…" or empty fails the check. The spec polls until GreenMail's Drafts holds exactly one reply draft, then asserts that draft contains `Here are the numbers ${run}.` and the attachment file name. I did not assert "Unsaved changes" right after typing, because the 10 s autosave timer can fire in that window and make the check flaky.

Validation:
- `npm --prefix web run e2e:typecheck` — Pass
- `npm --prefix web run e2e -- --project=phase-demos` — Pass (step 5 passed)

## Not fixed

None.

## Validation summary

Focused tests:
- `npm --prefix web run e2e:typecheck` — Pass
- `npm --prefix web run lint` — Pass (all files pass linting)
- `npx prettier --check e2e/phase5` (from web/) — Pass

Full test suite:
- `npm --prefix web run e2e -- --project=phase-demos` (docker compose e2e stack with GreenMail, built and started by run-e2e.mjs) — Pass, 30 passed (phases 0, 1, 2, 4 and 5; phase 5 steps 1-6 all passed)

Lint/type-check/build:
- Covered by `.taskcheck` below.

`.taskcheck`:
- `sh scripts/check.sh` — Pass on the second run. The first run, right after the e2e stack teardown, failed on 5 unrelated web unit tests that hit the 5 s timeout (event-dialog, files-page, inbox-page, next-view, general-settings), most likely from machine load. The second run passed: exit 0, 3267 web unit tests passed. This fix changed no web production or unit test code.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
