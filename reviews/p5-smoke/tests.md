# Test Report

Date: 2026-10-22

Task: p5-smoke - Phase 5 demo script (automate SPEC.md §13.2 Phase 5 against GreenMail)

Scope: base 769a79ea93bf64c0eb175f7323aed4c3d9dc2200 .. HEAD 47af9a40f91d35ec323dffcee3158043e8a1aa34.
Task commits: c2a6e1431 and 47af9a40f (rebased copies of 832c75142 and 7542b1ed8; the rebase onto 65b164a53
only added a `tasks/p5-smoke.md` line, so the code is identical). Changed files: `web/e2e/phase5/demo.spec.ts` (new),
`web/e2e/phase5/smtp.ts` (new), `deploy/docker-compose.e2e.yml`, `web/e2e/support/run-e2e.mjs`, `web/e2e/README.md`.
Plus this test-writer's uncommitted edit to `web/e2e/phase5/demo.spec.ts`.

Requirements:
- `tasks/p5-smoke.md` acceptance criterion: "`web/e2e/phase5` covers steps 1–6 (e2e stack includes GreenMail); earlier phase specs pass", and its recorded assumptions (SMTP port on the host, Sent sync through the e2e hook, toolbar actions with Delete -> Trash and Spam -> Junk)
- SPEC.md §13.2 Phase 5, steps 1–6
- AGENTS.md, docs/QUALITY.md, web/e2e/README.md (e2e conventions)

## Test files changed

- `web/e2e/phase5/demo.spec.ts`

## Tests added or updated

Count: 2 (steps 4 and 5 of the existing spec tightened, plus one new helper `frameMarkup`)

Review of each step against SPEC.md §13.2 Phase 5:

- Step 1: adds the IMAP/SMTP account through `/settings/mail` with "Add and test" and checks the "added and connected" status. Checks that the account's folders show in the tree (Inbox, Archive, Drafts, Sent, Trash, Spam, Projects) and that "All inboxes" lists all 6 seeded messages. Adequate.
- Step 2: delivers through GreenMail SMTP (not IMAP APPEND), waits for the row in the open list, and uses a `window` marker to show the page was not reloaded. Adequate.
- Step 3: for each of read, unread, star, move to Projects, archive, delete and spam, checks the UI and polls GreenMail over IMAP SEARCH (SEEN/UNSEEN/FLAGGED; the message is in the target folder and gone from INBOX). The final Inbox contents are checked on both sides. Adequate.
- Step 4 (weak, tightened): before, the spec only checked that the script and the `onerror` handler did not *run*. The iframe sandbox has no `allow-scripts` and the frame CSP has `script-src 'none'`, so that check would still pass with a broken server sanitizer. "Renders sanitized" was not shown. **Added:** checks before and after "Load images" that the rendered body has no `<script>` element, no `on*` attribute, and no script source text. **Added:** a page `request` listener checks that the browser never fetches `https://images.example.test/` directly (blocked first, then only through the image proxy).
- Step 5 (tightened): GreenMail receiving the mail with the text and attachment was already checked, and so were the draft in Drafts with the attachment and the draft being gone after sending. For Sent, only the count was checked. **Added:** the Sent copy holds the reply text and the attachment from Files.
- Step 6: "→ Inbox task" creates a task that is in `/inbox`, linked from the mail, and links back to the mail. "→ Waiting For" on the Sent copy creates an entry naming "Max Muster", the recipient. Adequate.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Step 1: IMAP/SMTP account (GreenMail), folders appear, unified inbox lists messages | `Phase 5 demo › 1. add an IMAP/SMTP account…` | `npm --prefix web run e2e -- --project=phase-demos e2e/phase5` | Pass |
| Step 2: SMTP-delivered message shows up without reload | `Phase 5 demo › 2. a message arriving via SMTP shows up without reload` | same | Pass |
| Step 3: read/unread, flag, move, archive, delete, spam reflected on GreenMail | `Phase 5 demo › 3. read/unread, flag, move, archive, delete and spam reach GreenMail` | same | Pass |
| Step 4: HTML with script and remote image rendered sanitized, images blocked until "load images" | `Phase 5 demo › 4. an HTML mail with a script and a remote image…` (tightened) | same | Pass |
| Step 5: reply with Files attachment, draft autosaved to Drafts, sent copy in Sent, received by GreenMail | `Phase 5 demo › 5. reply with an attachment from Files…` (tightened) | same | Pass |
| Step 6: "→ Inbox task" linked task, "→ Waiting For" from sent mail names recipient | `Phase 5 demo › 6. "→ Inbox task" creates a linked task…` | same | Pass |
| Earlier phase specs pass | phase0, phase1, phase2, phase4 demo specs (24 tests) | `npm --prefix web run e2e -- --project=phase-demos` | Pass (30 passed in total, phase 5 included) |
| e2e stack includes GreenMail | the stack started `cockpit-e2e-72159f73_greenmail_1`; steps 1–6 use it over IMAP and SMTP | same | Pass |

## Focused test execution

Command:

`npm --prefix web run e2e -- --project=phase-demos e2e/phase5` (builds and starts the podman-compose e2e stack itself), 2026-10-22T11:20:01+02:00 to 11:21:33+02:00, exit 0

Result:

Pass: 6 passed (21.8s)

Also: `npm --prefix web run e2e:typecheck` passed, and `prettier --write` and `eslint web/e2e/phase5` gave no findings.

## Full test execution

Command:

`npm --prefix web run e2e -- --project=phase-demos`, 2026-10-22T11:21:39+02:00 to 11:23:39+02:00, exit 0

Result:

Pass: 30 passed (1.1m). Phases 0, 1, 2 and 4 plus phase 5; there is no web spec for phase 3 (Android).

## .taskcheck

Result:

Pass: `sh scripts/check.sh`, 2026-10-22T11:23:44+02:00 to 11:25:02+02:00, exit 0, "all checks passed"

## Bugs found

None. Every tightened assertion passed against the current production code.

## Coverage

Status: not applicable. The deliverable is an end-to-end test spec plus e2e stack wiring, not production code.

Metric: whole-file coverage of task-changed production files. The task changed no production source files, so the scope is empty and there is no meaningful line or branch metric (N/A, not 100%).
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p5-smoke; base 769a79ea93bf64c0eb175f7323aed4c3d9dc2200; HEAD 47af9a40f91d35ec323dffcee3158043e8a1aa34; task commits c2a6e1431 and 47af9a40f, given by the parent and checked with `git log`/`git diff --stat`
- Source/test snapshot identifier (including uncommitted inputs): HEAD 47af9a40f plus uncommitted `web/e2e/phase5/demo.spec.ts` sha256 be3f49e2694a25d813045baeaa9baf0794137cca6892a406afa90cce1682cbf1; `web/e2e/phase5/smtp.ts` sha256 93f03db5789aa3f0d4ee4ebcbb7cc74bf4ef0dc93e4fb289de5cad2ea4e2fecd (as committed)
- Exact included files; renamed/deleted/excluded files and reasons: none included. Excluded: `web/e2e/phase5/demo.spec.ts` and `web/e2e/phase5/smtp.ts` (test code), `deploy/docker-compose.e2e.yml` and `web/e2e/support/run-e2e.mjs` (e2e test infrastructure, exercised by every e2e run above), `web/e2e/README.md` (docs)
- Provider/version, command, working directory, and test selection: no coverage provider applies. Instead, the behavior coverage is the step-to-assertion mapping above, measured by the Playwright runs in the repository root (`--project=phase-demos e2e/phase5` and the full `--project=phase-demos`)
- Run start/completion time and exit status: see Focused and Full test execution (both exit 0)
- Generated report paths and report hashes/run identifier: no coverage report. Playwright logs are in `.tmp/p5-e2e.log` and `.tmp/p5-all.log` (Git-ignored); compose project `cockpit-e2e-72159f73`
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both runs used the final spec content (hash above). The HEAD move from 7542b1ed8 to 47af9a40f during the session only touched `tasks/`
- Final run after latest test additions and production fixes: yes (no production fixes)

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/e2e/phase5/demo.spec.ts` step 4 | "renders sanitized": before, only "did not execute" was checked, which the sandbox and CSP alone guarantee | added DOM checks (no script, no `on*`, no script source) and the no-direct-fetch check |
| `web/e2e/phase5/demo.spec.ts` step 5 | "the sent mail is in Sent": only the count was checked | added a content and attachment check of the Sent copy |
| Step 2 "IDLE + SSE" | the spec shows arrival without reload, but it does not separate IDLE from some other push or poll path | accepted: it checks the user-visible behavior. The server-side IDLE path belongs to the `mail-idle` task tests |
| Step 6 Sent copy | read through `POST /api/test/mail/sync` rather than the 5-minute folder poll (assumption recorded on the task) | accepted: the poll interval is not part of the step |

## Unable to test

- SPEC.md §13.2 Phase 5 step 7 (real Gmail and Microsoft 365 over OAuth) is a human (H) step and out of scope for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Two weak assertions (step 4 sanitization, step 5 Sent copy) were tightened, and the phase 5 spec and the full phase-demos suite (30 tests) plus `.taskcheck` pass on the final spec.
