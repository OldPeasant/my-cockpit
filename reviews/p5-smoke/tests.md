# Test Report

Date: 2026-10-22

Task: p5-smoke - Phase 5 demo script (automate SPEC.md §13.2 Phase 5 against GreenMail)

Scope: re-run on the final code. Base 769a79ea93bf64c0eb175f7323aed4c3d9dc2200 .. HEAD
5a3ae6798ca90d2658f3deb4e846e7662b35b9a4, `git diff 769a79ea9..HEAD -- . ':!tasks' ':!reviews'`.
Task code commits: 829884b59, df24a5eef, 45b59ce1b, beb8f3c55 (the fixer's CR-1..CR-3 commit). The other commits in the
range touch only `tasks/` or `reviews/`. Changed files: `web/e2e/phase5/demo.spec.ts` (new), `web/e2e/phase5/smtp.ts`
(new), `deploy/docker-compose.e2e.yml`, `web/e2e/support/run-e2e.mjs`, `web/e2e/README.md`, `docs/ARCHITECTURE.md`.
Working tree was clean during the run. The earlier report was for HEAD 47af9a40f and is replaced by this one.

Requirements:
- `tasks/p5-smoke.md` acceptance criterion: "`web/e2e/phase5` covers steps 1–6 (e2e stack includes GreenMail); earlier phase specs pass", and its recorded assumptions (SMTP port on the host, toolbar actions with Delete -> Trash and Spam -> Junk, and the corrected step-6 assumption: no sync hook for Sent)
- SPEC.md §13.2 Phase 5, steps 1–6 (step 7 is a human step)
- AGENTS.md, docs/QUALITY.md, docs/ARCHITECTURE.md, web/e2e/README.md (e2e conventions)
- reviews/p5-smoke/review.md and fixes.md (CR-1..CR-3)

## Test files changed

- None in this re-run. The final spec at HEAD is unchanged by this test-writer.

## Tests added or updated

Count: 0

I reviewed the final spec step by step against SPEC.md §13.2 Phase 5, with focus on the fixer's changes:

- Step 1: adds the IMAP/SMTP account through `/settings/mail` with "Add and test" and checks the "added and connected" status. Checks that the folder tree shows Inbox, Archive, Drafts, Sent, Trash, Spam and Projects, and that "All inboxes" lists the seeded messages. Adequate.
- Step 2: delivers over GreenMail SMTP and waits for the row in the open list. A `window` marker shows the page did not reload. Adequate.
- Step 3: does read, unread, star, move to Projects, archive, delete and spam through the toolbar. After each one, it checks the UI and polls GreenMail over IMAP SEARCH. Then it checks the Inbox contents left on both sides. Adequate.
- Step 4: checks the sandbox attribute, that the script and handler did not run, and that no `<script>`, `on*` attribute or script source text is left in the rendered body. Images stay blocked until "Load images", then load only through the image proxy, and the browser never fetches the remote host directly. Adequate.
- Step 5 (CR-3 fix): after the file from Files is attached, the compose status must read "Draft saved <time>". Then exactly one reply draft must be in GreenMail Drafts, holding the typed text and the attachment. I checked whether this could be flaky: `DraftService.store` (server/.../mail/compose/DraftService.kt:470ff) appends the new version and expunges the old one inside the save request. So once the status shows "Draft saved", the single draft GreenMail holds is the final version, and the content check does not race an older draft. The spec also checks that the peer received the text and file, that Sent holds them, and that the draft is gone. Adequate.
- Step 6 (CR-1 fix): the `POST /api/test/mail/sync` hook is gone. The reply is now opened from the cockpit's Sent folder right after sending, so a broken post-send resync of Sent would fail the step. "→ Inbox task" makes a task that is in `/inbox` and links both ways. "→ Waiting For" on the Sent copy names "Max Muster". Adequate.

No new assertions were needed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Step 1: IMAP/SMTP account (GreenMail), folders appear, unified inbox lists messages | `Phase 5 demo › 1. add an IMAP/SMTP account: the folders appear and the unified inbox lists the messages` | `npm --prefix web run e2e -- --project=phase-demos` | Pass |
| Step 2: SMTP-delivered message shows up without reload | `Phase 5 demo › 2. a message arriving via SMTP shows up without reload` | same | Pass |
| Step 3: read/unread, flag, move, archive, delete, spam reflected on GreenMail | `Phase 5 demo › 3. read/unread, flag, move, archive, delete and spam reach GreenMail` | same | Pass |
| Step 4: HTML with script and remote image renders sanitized, images blocked until "load images" | `Phase 5 demo › 4. an HTML mail with a script and a remote image renders sanitized, images blocked until "load images"` | same | Pass |
| Step 5: reply with Files attachment, draft autosaved to Drafts, sent copy in Sent, received by GreenMail | `Phase 5 demo › 5. reply with an attachment from Files: draft in Drafts, sent mail in Sent, received by GreenMail` | same | Pass |
| Step 6: "→ Inbox task" linked task, "→ Waiting For" from a sent mail names the recipient (no sync hook) | `Phase 5 demo › 6. "→ Inbox task" creates a linked task; "→ Waiting For" from a sent mail names the recipient` | same | Pass |
| Earlier phase specs pass | phase0 (5), phase1 (10), phase2 (6), phase4 (3) demo specs, 24 tests | same | Pass (30 passed in total, phase 5 included) |
| e2e stack includes GreenMail (IMAP and SMTP) | the stack started `cockpit-e2e-72159f73_greenmail_1` (`greenmail/standalone:2.1.5`, SMTP published via `E2E_SMTP_PORT`); steps 1–6 use it over IMAP and SMTP | same | Pass |
| e2e specs type-check | all e2e specs and support | `npm --prefix web run e2e:typecheck` | Pass |

## Focused test execution

Command:

`npm --prefix web run e2e:typecheck` (`tsc -p e2e/tsconfig.json`), repository root, 2026-10-22T11:38:04+02:00 to 11:38:05+02:00, exit 0

Result:

Pass (no type errors)

## Full test execution

Command:

`npm --prefix web run e2e -- --project=phase-demos`, repository root. `run-e2e.mjs` built and started the podman-compose e2e stack itself (compose project `cockpit-e2e-72159f73`, server on port 43765, "stack healthy after 41 s"), then tore it down. 2026-10-22T11:38:09+02:00 to 11:40:23+02:00, exit 0.

Result:

Pass: 30 passed (1.0m), 1 worker. Phases 0, 1, 2 and 4, plus phase 5 steps 1–6. There is no web spec for phase 3 (Android). During teardown podman logged SIGKILL warnings for the greenmail/server containers. That is a normal stop timeout and does not affect the result.

## .taskcheck

Result:

Pass: `sh scripts/check.sh`, 2026-10-22T11:40:38+02:00 to 11:41:30+02:00, exit 0, "all checks passed"

## Bugs found

None. All tests passed against the current production code.

## Coverage

Status: not applicable. The deliverable is an end-to-end test spec, its SMTP helper, e2e stack wiring and docs. It is not production code.

Metric: whole-file coverage of task-changed production files. The task changed no production source files, so the scope is empty and there is no line or branch metric (N/A, not 100%). The e2e stack runs the packaged server image and the built web app, and the project's coverage tooling (ARCHITECTURE.md "Coverage": server JaCoCo on `./gradlew` tests, web unit-test coverage) does not instrument Playwright runs against that stack.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p5-smoke; base 769a79ea93bf64c0eb175f7323aed4c3d9dc2200; HEAD 5a3ae6798ca90d2658f3deb4e846e7662b35b9a4; task code commits 829884b59, df24a5eef, 45b59ce1b, beb8f3c55, given by the parent and checked with `git log`/`git diff --stat` on the range excluding `tasks/` and `reviews/`
- Source/test snapshot identifier (including uncommitted inputs): HEAD 5a3ae6798, clean working tree (`git status --short` empty before and after). sha256: `web/e2e/phase5/demo.spec.ts` 173532982c0945ace8b1e62056bbb24f52cc2ef9f9c2c2c617d2a6d5571a6e99, `web/e2e/phase5/smtp.ts` 93f03db5789aa3f0d4ee4ebcbb7cc74bf4ef0dc93e4fb289de5cad2ea4e2fecd, `deploy/docker-compose.e2e.yml` a97e2595b8e391d5256ee6d4ffcbeabde523f83082e4bf558359dd16c4d00a1f, `web/e2e/support/run-e2e.mjs` faaca37b9420d8135015e751c070e70d40c86f90f2e3a02efcdaef03e03f07b9
- Exact included files; renamed/deleted/excluded files and reasons: no files included. Excluded: `web/e2e/phase5/demo.spec.ts` and `web/e2e/phase5/smtp.ts` (test code); `deploy/docker-compose.e2e.yml` and `web/e2e/support/run-e2e.mjs` (e2e test infrastructure, run by the e2e run above); `web/e2e/README.md` and `docs/ARCHITECTURE.md` (docs). No renames or deletions.
- Provider/version, command, working directory, and test selection: no coverage provider applies (see above). The behavior coverage is the step-to-test mapping above, from the Playwright run `npm --prefix web run e2e -- --project=phase-demos` in the repository root
- Run start/completion time and exit status: typecheck 11:38:04 to 11:38:05 exit 0; e2e 11:38:09 to 11:40:23 exit 0; `.taskcheck` 11:40:38 to 11:41:30 exit 0 (all 2026-10-22, +02:00)
- Generated report paths and report hashes/run identifier: no coverage report. Playwright log at `.tmp/p5-rerun-all.log` (Git-ignored, sha256 6167bbca46548b9ea55a8cf140701d90ccf6962e98c84c54c4344c1ac94324a0); `.taskcheck` log at `.tmp/p5-rerun-check.log`; compose project `cockpit-e2e-72159f73`
- Evidence outputs are fresh and inputs stayed unchanged: yes. The file hashes and HEAD were the same before and after the runs, and the tree stayed clean
- Final run after latest test additions and production fixes: yes. It ran on HEAD 5a3ae6798, which includes the fixer commit beb8f3c55. There were no production fixes.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| Step 2 "IDLE + SSE" | the spec shows the mail arriving without a reload, but it does not tell IDLE apart from another push or poll path | accepted: the spec checks what the user sees. The server-side IDLE path belongs to the `mail-idle` task's tests |
| Step 5 "draft autosaves" | the save checked is the one the attach triggers, not the 10 s autosave timer | accepted: the fixer explains that asserting "Unsaved changes" right after typing would be flaky. The draft's saved content (text and file) on GreenMail is checked |

## Unable to test

- SPEC.md §13.2 Phase 5 step 7 (real Gmail and Microsoft 365 over OAuth) is a human (H) step and out of scope for this task. No follow-up needed here.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The final spec (including the fixer's CR-1 and CR-3 changes) covers steps 1–6, and no test changes were needed. `e2e:typecheck` passes, the full phase-demos suite passes (30 tests, phases 0, 1, 2, 4 and 5) against the podman-compose e2e stack with GreenMail, and `.taskcheck` passes.
