# Test Report

Date: 2026-10-26

Task: p6-smoke - Phase 6 demo script

Scope: base f470e9b96d1a24a59b723fb4e7f79f9e2477cad0 .. final revision 11e0ce729f880c72ddf42d9edee3dfb2534cbbe6 (branch main, working tree clean apart from this report). Task commits: 830125a25 (phase 6 demo spec + e2e profile property), ec250bb62 (test-writer's birthday-calendar assertions in step 1, plus reports), 11e0ce729 (fix CR-1: README paragraph). Changed non-report paths: `web/e2e/phase6/demo.spec.ts` (new Playwright spec, the deliverable), `server/src/main/resources/application-e2e.properties` (+1 property, e2e profile only), `web/e2e/README.md` (+1 paragraph, documentation).

This is a re-run on the final revision. Since the previous report (written on 830125a25 plus an uncommitted edit), the only changes are: the test-writer's step 1 edit, now committed unchanged in ec250bb62 (same sha256 dcabaff0... as the uncommitted edit the previous report described), and the README paragraph from fix CR-1 (11e0ce729). Neither changes production code.

Requirements:
- `tasks/p6-smoke.md` acceptance criterion and its four recorded assumptions (step 2 bookmarks the server's own page; step 5 restores into the same server; weekly reviews not in backups yet; habit checks on Mon-Wed of next week with a moved clock)
- SPEC.md §13.2 "Phase 6" demo steps 1-5
- AGENTS.md (Evidence rules), parent instructions (do not run `npm run e2e` or Gradle)
- Style of `web/e2e/phase2/demo.spec.ts`, `web/e2e/phase5/demo.spec.ts`

## Test files changed

- `web/e2e/phase6/demo.spec.ts` (test 1 only; committed in ec250bb62, sha256 dcabaff0f0a8e2efd6b4dfe96855771f994fcb0d60dfd8e8176d334636d9bd40). No test files were changed in this re-run.

## Tests added or updated

Count: 1 (test "1. contacts created and imported, the birthdays calendar; ..." tightened in the previous run; no further changes in this re-run)

What the step 1 edit adds, right after the existing birthday month-view assertion:
- the calendar panel lists a calendar named exactly "Birthdays" (`.calendar-list .calendar-name`);
- clicking Mira's birthday opens the event dialog showing the virtual occurrence (`.virtual-title` contains Mira's run-suffixed name followed by her age in parentheses), the calendar name "Birthdays" and the read-only note "This date comes from a contact";
- "Open contact" closes the dialog and leads to Mira's contact page (URL `/contacts/` followed by a UUID, h1 = Mira's name).

Why: without it, step 1 proved only that an event titled with the contact name and age appears on 3 December. It did not show that the event belongs to the Birthdays calendar or that the user "sees the birthdays calendar" as a calendar.

Re-run review of the final revision:
- The committed step 1 assertions are identical to the ones previously type-checked and linted (hash match).
- The README paragraph (CR-1) states that the e2e profile allows private and loopback fetches and that the server unit tests cover the refusal. I checked this claim and it is accurate: `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherTests.kt` ("hosts with a private, loopback or link-local address are refused before any request", "a loopback address in the URL itself is refused") and `PageFetcherEdgeCaseTests.kt` build the fetcher with `allowPrivateNetworks = false`, independent of Spring profiles. No test is needed for a documentation paragraph.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Step 1: create contacts (form), import a vCard, birthdays calendar, contact page shows mails, events, Waiting For | `1. contacts created and imported, ...` | parent: `cd web && npm run e2e` on 11e0ce729 | Not run by the test-writer (parent instruction). Pass on 830125a25 before the step 1 edit (parent run, 35 passed). The parent's run on 11e0ce729 is the first execution of the committed birthday-calendar assertions |
| Step 2: URL shared to Read later (API as Android), title + favicon fetched, in the web queue | `2. a URL shared to Read later ...` | parent: same | Pass on 830125a25 (parent run); unchanged since. Favicon verified via API only, see "Unable to test" |
| Step 3: today's journal entry with an image; 3x/week habit streak after 3 checks | `3. today's journal entry ...` | parent: same | Pass on 830125a25 (parent run); unchanged since |
| Step 4: weekly review wizard end to end; dashboard "Last weekly review: today" | `4. a weekly review completed ...` | parent: same | Pass on 830125a25 (parent run); unchanged since |
| Step 5: backup round trip covers all new entities | `5. the backup round trip ...` | parent: same | Pass on 830125a25 (parent run) and on 11e0ce729 (parent's full e2e run, as reported by the parent: 64 passed, all 5 phase 6 steps incl. the new step 1 birthday assertions; `sh scripts/check.sh` passed) for contacts, bookmarks, journal (incl. image), habits + checks. Weekly reviews: Not testable yet (no backup contributor, task backup-contrib-review not started yet; spec annotates and auto-checks once present) |
| Earlier phase specs pass | phase 0-5 demo specs | parent: same | Pass on 830125a25 (parent run, 35 passed incl. phase 0-6); full e2e suite on 11e0ce729 being run by the parent |
| Spec compiles, lints, is formatted | whole `web/e2e` (typecheck); `e2e/phase6`, `e2e/README.md` (lint/format) | `cd web && npm run e2e:typecheck`; `cd web && npx eslint e2e/phase6`; `cd web && npx prettier --check e2e/phase6 e2e/README.md` | Pass on 11e0ce729 (this re-run) |

Review of the spec's assertions per step (no defects found, unchanged from the previous run):
- Step 1: contact created via the form (URL + h1), vCard import status text and exactly 3 matching rows, birthday on the right date with age, in the Birthdays calendar, read-only, linking back to the contact; contact page checked on the web for email, Waiting For link, event link and mail ("From" direction, link opens the message).
- Step 2: `metadataStatus` polled to FETCHED (`expect.poll`, no fixed sleeps), title "My Cockpit" and favicon `http://localhost:8080/favicon.svg`. Web queue: "Read later" tab is `aria-current=page`, the item has the fetched title, the URL, and no "fetching title..." marker.
- Step 3: entry written in the editor, image pasted, "Saved" status, body checked via API and image rendered in Preview with `naturalWidth` 2. Habit streak read on the web after each check (0, 0, 1 week) and after a reload; check counts confirmed via API.
- Step 4: all 8 wizard steps traversed, the inbox item processed inside the review, Waiting For item shown, reflection saved; dashboard shows "Today" and no "Start a weekly review" prompt, and the readout links to /review.
- Step 5: backup created and downloaded through the web UI; zip contents checked; data changed after the backup (snapshot asserted to differ), restore via the web UI, full API snapshot compared with `toEqual`, restored journal image renders. `expectDemoData` first asserts that the snapshot holds the step 1-4 data, so the comparison is not vacuous.
- Flakiness: waits use web-first assertions / `expect.poll`; clocks moved deterministically; serial mode; `afterAll` calls `/api/test/reset`, which also resets the server clock. No real-time sleeps.

Server side, `cockpit.bookmarks.fetch.allow-private-networks=true` in `application-e2e.properties` (unchanged since the previous run):
- No server test with `@ActiveProfiles("e2e")` exercises bookmark fetching or SSRF blocking; the SSRF tests (`PageFetcherTests`, `PageFetcherEdgeCaseTests`, `BookmarkMetadataWorkerTests`) build `BookmarkFetchProperties` directly. Expected impact: none.
- No other web e2e spec uses bookmarks.
- Gradle not run here (sub-agents cannot run Gradle in this environment; the parent runs `.taskcheck`).

## Focused test execution

Command:

`cd web && npm run e2e:typecheck`; `cd web && npx eslint e2e/phase6`; `cd web && npx prettier --check e2e/phase6 e2e/README.md` (run on 11e0ce729, 2026-10-26 ~06:37 +01:00)

Result:

Pass (tsc: no errors; ESLint: no findings; Prettier: "All matched files use Prettier code style!"). The test-writer did not run the Playwright suite itself (parent instruction: the parent is running it on 11e0ce729).

## Full test execution

Command:

`cd web && npm run e2e` (full e2e suite on 11e0ce729, being run by the parent)

Result:

Not run by the test-writer. The previous parent run (`npm run e2e -- --project=phase-demos` on 830125a25) passed with 35 tests. The result on 11e0ce729 is the parent's to record; it is the first execution of the committed step 1 birthday-calendar assertions.

## .taskcheck

Result:

Not run (includes Gradle, which sub-agents cannot run here; the parent runs it).

## Bugs found

None.

## Coverage

Status: not applicable. The task delivers an end-to-end Playwright script, one profile property and a README paragraph. The project's coverage tooling (docs/ARCHITECTURE.md "Coverage": JaCoCo for server and Android unit/integration tests, Vitest + `@vitest/coverage-v8` for web unit tests) instruments production code under those test runners. It does not instrument Playwright runs against the containerized stack. The task changes no instrumented production code: a `.properties` line and a Markdown paragraph have no executable statements.

Metric: whole-file coverage of task-changed production files - N/A (no executable production files in scope).
Changed-line coverage: not measured (no executable changed lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p6-smoke; base f470e9b96d1a24a59b723fb4e7f79f9e2477cad0 (from the parent); final revision 11e0ce729f880c72ddf42d9edee3dfb2534cbbe6; task commit list `git log f470e9b96..HEAD` = 830125a25, ec250bb62, 11e0ce729. This matches the task's `touches: [web/e2e/phase6]`, plus the property line recorded as an assumption and the CR-1 README paragraph.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 11e0ce729 with no uncommitted source/test changes; `web/e2e/phase6/demo.spec.ts` sha256 dcabaff0f0a8e2efd6b4dfe96855771f994fcb0d60dfd8e8176d334636d9bd40; `server/src/main/resources/application-e2e.properties` sha256 340852bfe3997d3c0c9c5abe1c971dc38e3798f38b66c0319f77346ea032c204; `web/e2e/README.md` sha256 77b114fc5ca3f5cb1835b9da3791208600a5584d7130be0736dd640b12a04f2a.
- Exact included files; renamed/deleted/excluded files and reasons: `web/e2e/phase6/demo.spec.ts` excluded (test code); `server/src/main/resources/application-e2e.properties` excluded (configuration, no executable statements); `web/e2e/README.md` excluded (documentation). No renames or deletions.
- Provider/version, command, working directory, and test selection: none run (not applicable, see above).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A, no coverage output used.
- Final run after latest test additions and production fixes: no coverage run applies. Static checks were run on the final revision 11e0ce729; the Playwright run on 11e0ce729 is the parent's.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (none: no executable production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `server/src/main/resources/application-e2e.properties:8` | Lets the e2e server's page fetcher reach private addresses | Exercised by phase 6 step 2 (bookmark of `http://localhost:8080/` reaches FETCHED); no code coverage applicable. SSRF refusal stays covered by `PageFetcherTests`/`PageFetcherEdgeCaseTests` (`allowPrivateNetworks = false`) |

## Unable to test

- Step 2 on Android (sharing from the share sheet): replaced by the equivalent API call, as the criterion allows. The device part is the human task h-android-device. No follow-up for this task.
- Favicon on the web: the bookmarks page deliberately shows no remote favicon (CSP, IP leak; see `bookmarks-page.html`), so "favicon fetched" can only be shown via the API (`favicon` field). This is the intended behavior, not a gap.
- Weekly reviews in the backup (SPEC.md §14 "all new entities"): there is no backup contributor yet (task backup-contrib-review, not started yet, not a dep of p6-smoke). The spec annotates the run and checks "last weekly review" after the restore automatically once `data/weekly_reviews.json(l)` appears. Until that task lands, step 5 is only partially proven. The parent has already recorded this as an assumption.
- Playwright execution of the final revision and the server Gradle tests: not run by the test-writer (parent instruction / environment); the parent is running them on 11e0ce729.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. On the final revision 11e0ce729, no further test changes were needed. The committed step 1 edit is byte-identical to the one reviewed before. The CR-1 README paragraph is accurate: the server unit tests do cover SSRF refusal. The e2e typecheck, ESLint and Prettier pass. Task-scoped code coverage does not apply to this e2e-only task. Two things remain open. First, the parent's full e2e run on 11e0ce729 is the first execution of the committed birthday-calendar assertions, and its result must be recorded. Second, weekly reviews stay outside the backup round trip until backup-contrib-review lands.
