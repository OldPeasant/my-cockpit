# Test Report

Date: 2026-10-26

Task: p6-smoke - Phase 6 demo script

Scope: base f470e9b96d1a24a59b723fb4e7f79f9e2477cad0 .. HEAD 830125a25b0d33065dfe15d37fa7e26d05d28f62 (one task commit, branch main), plus one uncommitted test-writer edit to `web/e2e/phase6/demo.spec.ts`. Changed paths: `web/e2e/phase6/demo.spec.ts` (new Playwright spec, the deliverable), `server/src/main/resources/application-e2e.properties` (+1 property, e2e profile only).

Requirements:
- `tasks/p6-smoke.md` acceptance criterion and its four recorded assumptions (step 2 bookmarks the server's own page; step 5 restores into the same server; weekly reviews not in backups yet; habit checks on Mon-Wed of next week with a moved clock)
- SPEC.md §13.2 "Phase 6" demo steps 1-5
- AGENTS.md (Evidence rules), parent instructions (do not run `npm run e2e` or Gradle)
- Style of `web/e2e/phase2/demo.spec.ts`, `web/e2e/phase5/demo.spec.ts`

## Test files changed

- `web/e2e/phase6/demo.spec.ts` (test 1 only; uncommitted, sha256 dcabaff0f0a8e2efd6b4dfe96855771f994fcb0d60dfd8e8176d334636d9bd40)

## Tests added or updated

Count: 1 (test "1. contacts created and imported, the birthdays calendar; ..." tightened)

What changed, right after the existing birthday month-view assertion in step 1:
- the calendar panel lists a calendar named exactly "Birthdays" (`.calendar-list .calendar-name`);
- clicking Mira's birthday opens the event dialog showing the virtual occurrence (`.virtual-title` contains "Mira Holloway<run> (age)"), the calendar name "Birthdays" and the read-only note "This date comes from a contact";
- "Open contact" closes the dialog and leads to Mira's contact page (URL `/contacts/<uuid>`, h1 = Mira's name).

Why: before, step 1 proved only that an event titled "<name> (<age>)" appears on 3 December; it did not show that the event belongs to the Birthdays calendar or that the user "sees the birthdays calendar" as a calendar. The locators follow existing code (`calendar-view.html`, `event-dialog.html`, unit test `event-dialog.spec.ts` "birthdays (virtual calendar)") and the click precedent in `web/e2e/calendar-overlay/overlay.spec.ts`.

The parent must re-run the phase 6 spec (e.g. `npm run e2e -- --project=phase-demos`): this edit has been type-checked, linted and format-checked only, not executed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Step 1: create contacts (form), import a vCard, birthdays calendar, contact page shows mails, events, Waiting For | `1. contacts created and imported, ...` | parent: `npm run e2e -- --project=phase-demos` | Pass on the committed revision (parent, 35 passed). Not run after the test-writer edit: re-run needed |
| Step 2: URL shared to Read later (API as Android), title + favicon fetched, in the web queue | `2. a URL shared to Read later ...` | parent: same | Pass (parent run). Favicon verified via API only, see "Unable to test" |
| Step 3: today's journal entry with an image; 3x/week habit streak after 3 checks | `3. today's journal entry ...` | parent: same | Pass (parent run) |
| Step 4: weekly review wizard end to end; dashboard "Last weekly review: today" | `4. a weekly review completed ...` | parent: same | Pass (parent run) |
| Step 5: backup round trip covers all new entities | `5. the backup round trip ...` | parent: same | Pass (parent run) for contacts, bookmarks, journal (incl. image), habits + checks. Weekly reviews: Not testable yet (no backup contributor, task backup-contrib-review is todo; spec annotates and auto-checks once present) |
| Earlier phase specs pass | phase 0-5 demo specs | parent: same | Pass (parent run, 35 passed incl. phase 0-6); full e2e suite being run by the parent |
| Spec compiles, lints, is formatted | whole `web/e2e` | `cd web && npm run e2e:typecheck`; `npx eslint e2e/phase6 && npx prettier --check e2e/phase6` | Pass (after the edit) |

Review of the spec's assertions per step (no defects found):
- Step 1: contact created via the form (URL + h1), vCard import status text and exactly 3 matching rows, birthday on the right date with age; contact page checked on the web for email, Waiting For link, event link and mail ("From" direction, link opens the message). Mail/event/Waiting For are seeded via IMAP/API, which is fine: the step's observable is the contact page.
- Step 2: `metadataStatus` polled to FETCHED (`expect.poll`, no fixed sleeps), title "My Cockpit" and favicon `http://localhost:8080/favicon.svg` (parsed from the page's `<link rel=icon>`, not a `/favicon.ico` fallback). Web queue: "Read later" tab is `aria-current=page`, the item has the fetched title, the URL, and no "fetching title..." marker.
- Step 3: entry written in the editor, image pasted, "Saved" status, body checked via API and image rendered in Preview with `naturalWidth` 2 (proves the file is served, not just a broken `<img>`). Habit streak is read on the web after each check (0, 0, 1 week) and after a reload; check counts confirmed via API.
- Step 4: all 8 wizard steps traversed, the inbox item processed inside the review, Waiting For item shown, reflection saved; dashboard shows "Today" and no "Start a weekly review" prompt, and the readout links to /review.
- Step 5: backup created and downloaded through the web UI; zip contents checked; data changed after the backup (snapshot asserted to differ), restore via the web UI, full API snapshot compared with `toEqual`, restored journal image renders. Not a vacuous comparison: `expectDemoData` asserts the snapshot holds the step 1-4 data first.
- Flakiness: waits use web-first assertions / `expect.poll`; clocks moved deterministically; serial mode; `afterAll` calls `/api/test/reset`, which also resets the server clock (TestSupportController). No real-time sleeps found.

Server side, `cockpit.bookmarks.fetch.allow-private-networks=true` in `application-e2e.properties`:
- Server tests with `@ActiveProfiles("e2e")`: `support/ProfileTests.kt` (E2e: JSON log format, datasource override), `core/testsupport/TestSupportWebTests.kt`, `core/testsupport/TestSupportLoginWebTests.kt`. None of them exercises bookmark fetching or SSRF blocking. `DatasourcePropertiesTests.kt` and `SessionCookiesTests.kt` read the e2e file as properties but check only datasource and `cockpit.auth.secure-cookie` keys. The SSRF-blocking tests (`BookmarkMetadataWorkerTests`) build `BookmarkFetchProperties` directly with `allowPrivateNetworks = false`, independent of profiles. Expected impact: none.
- No other web e2e spec uses bookmarks (grep of `web/e2e`), so no e2e spec depends on private addresses being blocked.
- Parent should run: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.support.ProfileTests' --tests 'ch.sonensei.cockpit.support.DatasourcePropertiesTests' --tests 'ch.sonensei.cockpit.core.testsupport.*' --tests 'ch.sonensei.cockpit.core.auth.SessionCookiesTests' --tests 'ch.sonensei.cockpit.bookmarks.*'` (or the full `./gradlew check` via `.taskcheck`). Not run here: sub-agents cannot run Gradle in this environment.

## Focused test execution

Command:

`cd web && npm run e2e:typecheck` and `cd web && npx eslint e2e/phase6 && npx prettier --check e2e/phase6`

Result:

Pass (no type errors, no lint findings, "All matched files use Prettier code style!"). The Playwright run itself was not executed by the test-writer (parent instruction: it starts a podman stack).

## Full test execution

Command:

`cd web && npm run e2e -- --project=phase-demos` (run by the parent on commit 830125a25, before the test-writer edit)

Result:

Pass as reported by the parent (35 passed). Not run on the edited spec; the full e2e suite was being run by the parent at the time of writing.

## .taskcheck

Result:

Not run (includes Gradle, which sub-agents cannot run here; the parent runs it).

## Bugs found

None.

## Coverage

Status: not applicable. The task's deliverable is an end-to-end Playwright script plus one profile property; the project's coverage tooling (docs/ARCHITECTURE.md "Coverage": JaCoCo for server and Android unit/integration tests, Vitest + `@vitest/coverage-v8` for web unit tests) instruments production code under those test runners, not Playwright runs against the containerized stack, and the task changes no instrumented production code (a `.properties` line has no executable statements).

Metric: whole-file coverage of task-changed production files - N/A (no executable production files in scope).
Changed-line coverage: not measured (no executable changed lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p6-smoke; base f470e9b96d1a24a59b723fb4e7f79f9e2477cad0 (from the parent); HEAD 830125a25b0d33065dfe15d37fa7e26d05d28f62; task commit list `git log f470e9b96..HEAD` = 830125a25 only, matching the task's `touches: [web/e2e/phase6]` plus the property line recorded as an assumption.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 830125a25 + uncommitted `web/e2e/phase6/demo.spec.ts` sha256 dcabaff0f0a8e2efd6b4dfe96855771f994fcb0d60dfd8e8176d334636d9bd40; `server/src/main/resources/application-e2e.properties` sha256 340852bfe3997d3c0c9c5abe1c971dc38e3798f38b66c0319f77346ea032c204.
- Exact included files; renamed/deleted/excluded files and reasons: `web/e2e/phase6/demo.spec.ts` excluded (test code); `server/src/main/resources/application-e2e.properties` excluded (configuration, no executable statements). No renames or deletions.
- Provider/version, command, working directory, and test selection: none run (not applicable, see above).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A, no coverage output used.
- Final run after latest test additions and production fixes: no; no coverage run applies, and the Playwright spec must be re-run by the parent after the test-writer edit.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (none: no executable production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `server/src/main/resources/application-e2e.properties:8` | Lets the e2e server's page fetcher reach private addresses | Exercised by phase 6 step 2 (bookmark of `http://localhost:8080/` reaches FETCHED); no code coverage applicable |

## Unable to test

- Step 2 on Android (sharing from the share sheet): replaced by the equivalent API call, as the criterion allows; the device part is the human task h-android-device. No follow-up for this task.
- Favicon on the web: the bookmarks page deliberately shows no remote favicon (comment in `bookmarks-page.html`: the CSP blocks it and it would leak the owner's IP), so "favicon fetched" can only be shown via the API (`favicon` field). This is correct, not a gap in the spec.
- Weekly reviews in the backup (SPEC.md §14 "all new entities"): there is no backup contributor yet (task backup-contrib-review, status todo, not a dep of p6-smoke). The spec annotates the run and checks "last weekly review" after the restore automatically once `data/weekly_reviews.json(l)` appears. Step 5 is therefore only partially proven until that task lands. The parent has already recorded this as an assumption.
- Running the spec after the test-writer edit, and the server Gradle tests for the e2e profile: not run here (parent instructions / environment); the parent must run them.

## Summary

Tests added and all validation passed, as far as could be run here: I tightened step 1 and the edited spec passes the type check, ESLint and Prettier. The parent's e2e run of the committed revision passed, but nobody has run the edited spec through Playwright yet. The parent must re-run `npm run e2e -- --project=phase-demos` before relying on it. No production defects found, and task-scoped code coverage does not apply to an e2e-only task. One part of step 5 has not been proven: weekly reviews are not covered by the backup round trip until backup-contrib-review lands.
