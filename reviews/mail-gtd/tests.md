# Test Report

Date: 2026-10-21

Task: mail-gtd - Mail to GTD integration

Scope: final round (round 4). Base 0cdd3a188e0a35bd13a5754275338e819792e097 (given by the parent), measured HEAD aaf3c30c9384914fd307fa497d748ebe1e5eea2f on main, task commits `git log 0cdd3a18..HEAD` (all prefixed `mail-gtd:`). Production changes since the round-3 report (4121d8e0): ddf36a3c (to-note runs `neutralizeInlineLinks` on the whole note body, header display names included) and af618c04 (`[[` is written as `[ [` instead of `[\[`). Production files changed by the task (`git diff --stat 0cdd3a18..HEAD -- src/main` in `server/`): `MailGtdController.kt` and `MailGtdService.kt` in `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/`. The only uncommitted input from this round is the test file listed below. `reviews/mail-gtd/review.md` also shows as modified in the working tree; this round did not touch it.

Requirements:
- `tasks/mail-gtd.md` acceptance criteria (AC1 to-note creates a linked note, AC3 mail.gtd suite passes, AC4 `OpenApiContractTest`)
- `reviews/mail-gtd/review.md` final review CR-1 and `reviews/mail-gtd/fixes.md` round 3
- Parent instructions: check coverage of both changes, including a mail body with backslash escapes and brackets mixed with autolinks, so that note creation does not return 500
- `docs/ARCHITECTURE.md` "Coverage"

## Final round: test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`

## Final round: tests added or updated

Count: 2 added. The fix commits already updated `inline link tokens in a mail's text do not link the note to other items` (now expects `[ [`) and added `inline link tokens in display names and the subject do not link the note to other items`. That test covers change (1) for From, To and Cc (including `[[[`) and the subject. It is also the regression test for change (2): the header has bold labels, `<address>` autolinks and hard breaks.

- `to-note of an HTML mail with backslashes, brackets and a link creates the note`: the display name `Grace \[[x]]`, and an HTML part with `\[\[b]]`, a link, `<br>` and `[\[task:id]]\`. It expects 201, a note linked to the mail, the header `**From:** Grace \[ [x]] <grace@example.test>`, the converted text unchanged, and no `[[`. **Fails (TW-1).**
- `to-note of a mail with backslash escapes, brackets and autolinks next to the bold header creates the note`: a plain-text body with `\[\[`, `[\[`, `\\[[`, `\*` inside bold, `<https://…\_b>` and `<mail>` autolinks, a hard break, a trailing backslash, a bare URL with brackets, `[label](url)`, escaped backticks and `[[ [[ ]] \\`. The display names contain `\,`, `[\[`, `**x**` and `\[[note:id]]\`. It expects 201, a linked note, the exact neutralized header and body lines, and no `[[`. **Fails (TW-1).**

## Final round: requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| CR-1 fix: display names in the note header cannot add inline links | `inline link tokens in display names and the subject do not link the note to other items` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*'` | Pass |
| `[[` is neutralized as `[ [` in the text and HTML parts | `inline link tokens in a mail's text do not link the note to other items` | same | Pass |
| Change (2) regression: a neutralized `[[` next to bold, autolinks and hard breaks in the header does not make to-note fail | `inline link tokens in display names and the subject ...` (`[[[` in Cc) | same | Pass |
| AC1 to-note creates a linked note for a mail whose text or display names contain backslash escapes or brackets next to autolinks (parent's "does not 500") | the 2 new tests | same | **Fail: 500, TW-1** |
| AC3: the mail.gtd suite passes (Testcontainers Postgres) | all 50 tests | same | **Fail: 48 pass, 2 fail (TW-1)** |
| AC4: `OpenApiContractTest` passes | `OpenApiContractTest` | `cd server && ./gradlew test --tests '*OpenApiContractTest'` | Pass (1 test, 0 failures/errors/skipped) |

## Final round: focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*'` (2026-10-21T07:29:59+02:00 to about 07:31:03+02:00), then `./gradlew jacocoTestReport -x test ktlintCheck`

Result:

Fail: 50 tests, 2 failed. Both new tests get `Status expected:<201> but was:<500>`. The server logs `ProblemDetailsAdvice` "Unhandled exception in REST request" with `java.lang.AssertionError: length:58 != computeLength(): 60` (HTML test) and `length:157 != computeLength(): 163` (plain-text test). The other 48 pass. `ktlintCheck` passed on the main and test source sets. `OpenApiContractTest`: pass.

## Final round: full test execution

Command:

`sh scripts/check.sh`

Result:

Not run in this round. The server `./gradlew check` includes the two TW-1 failures, so the full check fails until TW-1 is fixed. The parent must run it after the fix. The round-3 full run (below) passed on the earlier revision.

## Final round: .taskcheck

Result:

Not run (same reason: it is `sh scripts/check.sh`, and it fails on TW-1 until the fix lands).

## Final round: bugs found

### to-note returns 500 when the mail's text or a display name contains a backslash next to `[`, autolinks and bold

Finding ID:

`TW-1`

Test:

`MailGtdWebTests.to-note of an HTML mail with backslashes, brackets and a link creates the note` and `MailGtdWebTests.to-note of a mail with backslash escapes, brackets and autolinks next to the bold header creates the note`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt:51-60` (`plainText`, flexmark `TextCollectingVisitor`), called from `NoteService` (`NoteService.kt:39`) when the note created by `MailGtdService.noteBody` (`server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt:132-157`) is indexed.

Requirement:

AC1 (to-note creates a note linked to the mail) for any received mail. The parent asked for exactly this check: a mail body with backslash escapes or brackets mixed with autolinks must not make note creation fail with 500.

Expected behavior:

201. A note with the neutralized text is created and linked to the mail.

Actual behavior:

500. `java.lang.AssertionError: length:N != computeLength(): M` from flexmark's text collection. Commit af618c04 stops *our* neutralization from adding `[\[`, but escapes the sender writes are still copied into the Markdown as they are. A minimal trigger is a sender display name `Grace \[[x]]`, which becomes the header `**From:** Grace \[ [x]] <grace@example.test>  \n**To:** Ann Example <ann@work.example>  \n**Date:** 2026-02-20 10:30\n\nHello` (found by calling `NoteMarkdown.plainText` directly in a temporary probe, which was deleted). Every display name and line of mail text is sender-controlled.

The `AssertionError` is a JVM `assert`. Gradle tests run with `-ea`, and nothing in the repo enables assertions at runtime. With assertions off, the same input gave no exception in the probe, but it gave garbled plain text (for example `From:** Grace [x`, where `**` is left in and characters are shifted). So without `-ea`, production gets wrong search and title text rather than a 500. The suite and any `-ea` runtime get a 500.

The root cause is in the notes module (`NoteMarkdown.plainText`), outside this task's touches. A user who types the same Markdown into a note would hit it too. Possible fixes, for the parent to choose: make `plainText` robust (for example, fall back to the raw text when collection fails, or collect text another way), or have `noteBody` also escape or neutralize backslashes that come from the mail.

Severity:

Medium. Any sender can make to-note fail (500) in test and `-ea` environments and degrade search text in production. No data is lost and no link is injected.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Final round: coverage

Status: partial-failed (diagnostic only). The focused test command exited nonzero because of TW-1. The JaCoCo report below was built by `./gradlew jacocoTestReport -x test` from the `build/jacoco/test.exec` that this failed run wrote. It is fresh for this revision, but it comes from a failing run, so it cannot be the passing sign-off. A new run after the TW-1 fix is required.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: added lines in `git diff -U0 <range> -- <file>`, intersected with JaCoCo XML `<line>` entries and counted as covered when `ci > 0` (script `.tmp/tw/cov.py`, Git-ignored). Task range `0cdd3a18..HEAD`: MailGtdController.kt 32/32, MailGtdService.kt 148/148. Since round 3 (`4121d8e0..HEAD`): MailGtdService.kt 2/2. Changed-branch coverage not derived.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-gtd; base 0cdd3a188e0a35bd13a5754275338e819792e097 (from the parent); HEAD aaf3c30c9384914fd307fa497d748ebe1e5eea2f; task commits from `git log 0cdd3a18..HEAD`, all `mail-gtd:`; file scope from `git diff --stat 0cdd3a18..HEAD -- src/main`.
- Source/test snapshot identifier (sha256, same before and after the run): MailGtdController.kt 1580681f3ec5fe7d2ae260bd74f959bca76da5b2b41ad3c6234d545869669eb3, MailGtdService.kt 361d1074c053aae0bf1a989698b678cee8fdbf19f50c4aae18969ae132dbcc55, MailGtdMailServerTests.kt 641e4fe28def59c09e3600a94d86f42e4581827e0d9877336c23a2dbe6c13cbe, MailGtdWebTests.kt (uncommitted, with the 2 new tests) 2e8342a8bc0892dbed7539b73d8d14b29e8595d558ba9b888543846fe4ea9e9b.
- Exact included files; renamed/deleted/excluded files and reasons: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt` and `MailGtdService.kt`, both measured. Nothing was renamed or deleted. TW-1's root cause, `NoteMarkdown.kt`, is outside the task scope and is not measured here.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default tool version); `./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*'`, then `./gradlew jacocoTestReport -x test ktlintCheck`, in `server/`; selection `ch.sonensei.cockpit.mail.gtd.*` (MailGtdWebTests 45, MailGtdMailServerTests 5).
- Run start/completion time and exit status: tests 07:29:59 to 07:31:03 +02:00, exit nonzero (2 failed). Report task 07:31:09, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (mtime 07:31:01.73, sha256 2441628cc6dd031bdd30a56fdc7a29455a20cbfb505c071e3647541948a6d284); `server/build/reports/jacoco/test/jacocoTestReport.xml` (mtime 07:31:09.45, sha256 b743bee1e0c4d389c945ee8431dcd0eb696c0622311760a277d70b9b16da1ad6).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The exec file and the report have mtimes inside this run. Snapshot hashes matched after the run.
- Final run after latest test additions and production fixes: yes for the tests added in this round. No: TW-1 is not fixed yet, so coverage must be measured again after the fix.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt` | 157/157 (100%) | 2/2 (100%) | 12/12 (100%) | 32/32 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt` | 1353/1376 (98.3%) | 115/126 (91.3%) | 41/42 (97.6%) | 148/148 (100%) |
| Aggregate (summed counters, both scoped files, none unmeasured) | 1510/1533 (98.5%) | 117/128 (91.4%) | 53/54 (98.1%) | 180/180 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailGtdService.kt:233-234` (1 branch each) | `htmlText` visiting a node that is neither a TextNode nor an Element | Justified gap (same as round 3): the sanitizer removes comments, scripts and styles |
| `MailGtdService.kt:106, 122, 123, 126, 159` (1 branch each) | Kotlin null-safety fallbacks | Justified gap (same as round 3): defensive code |
| `MailGtdService.kt:278, 280` (2 branches each) | `display`/`full` with a null name | Partly covered by the blank-name tests; the remaining bytecode variants are not material |
| `MailGtdService.kt:164` (`getDATE_FORMAT`) | Generated getter | Not material |
| `MailGtdService.kt:156` (whole-body neutralization) | CR-1 fix | Covered (line executed; display-name test passes). The new failing tests show the remaining TW-1 risk, which line coverage cannot show |

## Final round: unable to test

- Production runtime behavior without `-ea` was only checked in a temporary probe (a class loader with assertions disabled). No committed test asserts it, because the project's test JVM always runs with assertions on.
- Full `scripts/check.sh` / `.taskcheck`: not run, because TW-1 fails it. The parent must run it after the fix.

## Final round: summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1, Medium). Both changes since round 3 are covered by passing tests. The two new tests show that backslash escapes written by the sender still crash note indexing under `-ea`.

---

The sections below are the round-3 report (revision 1efb5405, base ce9e2a2d before main moved). They are kept for history and are stale for the final revision.

## Round 3 report

Scope: task commits `git log ce9e2a2dea0ba45410863b86ed8559157e82345f..1efb5405d25691ee7bdf5e3949804cb46d4f5ad1` on branch main (base = merge-base with origin/main, supplied by the parent; HEAD 1efb5405d25691ee7bdf5e3949804cb46d4f5ad1). Task commits: 5e8ed3ef, 913a1ceb, 8eeb2de7, 2e324611, 119398c5, c6916075, 010e3b87, b9f0a9d6, 21955657, 1efb5405. The latest production change is 21955657 (re-review CR-1/CR-2: whitespace collapsing outside `<pre>` and extra block tags in `MailGtdService.htmlText`); 1efb5405 only touches `reviews/`. This round focuses on the changed `htmlText` behavior. Production files changed by the task (`git diff --name-only ce9e2a2d..HEAD -- server/src/main web/src android/app/src/main`): `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt` and generated web client files under `web/src/app/api/generated/` (generated, excluded). The only uncommitted input is the test change listed below.

Requirements:
- `tasks/mail-gtd.md` acceptance criteria and recorded assumptions
- SPEC.md §7.3 (via the task), `docs/ARCHITECTURE.md` "Coverage"
- `reviews/mail-gtd/review.md` (re-review CR-1, CR-2) and `reviews/mail-gtd/fixes.md`, used to target the regression test
- Parent instructions (focus on htmlText edge cases: nested pre, nbsp, inline elements across whitespace, cells, br inside indented markup)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`

## Tests added or updated

Count: 1 (added)

- `to-note of indented HTML keeps nbsp, spaces inline elements once, keeps nested pre text and skips empty cells`: pretty-printed, indented HTML through the real to-note endpoint. It checks the exact text output for:
  - `&nbsp;` kept as U+00A0 inside text, and an `&nbsp;`-only paragraph (common in Outlook mail) dropped instead of adding a blank line;
  - a `<br>` followed by an indented source line, with no leading blank on the next line;
  - inline elements (`span`, `a`, `b` with a leading space) across source whitespace, with exactly one space between words;
  - `<pre><code>` nested inside an indented `<blockquote>`, with its indentation and newlines kept as written;
  - a table `caption`, cells with leading and trailing spaces, and an empty cell, giving `Q1 10` with no double or trailing space;
  - `details`/`summary`, `main` and `aside` (the CR-2 tags not used by the earlier test) as boundaries.

The fix commit 21955657 already added `to-note collapses the source indentation of a pretty-printed HTML part but keeps pre text` (CR-1) and `center` in the block-structure test (CR-2). Earlier rounds covered every acceptance criterion; see the table below.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: to-task creates a linked Inbox task | `to-task creates an Inbox task linked both ways to the mail`, `a repeated request creates another linked item`, `the response names the link that the links API returns`, title/limit tests | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' jacocoTestReport ktlintCheck` | Pass |
| AC1: to-waiting creates a linked Waiting For task; a sent mail waits for its recipient | `to-waiting from a sent mail waits for its recipients since the mail's date`, `a mail from one of the account's identities counts as sent outside the Sent folder`, `sent detection by the account address ignores case, outside the Sent folder`, `to-waiting from a sent mail without To waits for Cc, and without To and Cc for Bcc`, `to-waiting from a sent mail with To ignores Cc and Bcc`, `to-waiting from a received mail waits for its sender` (assumption), waitingSince tests | same | Pass |
| AC1: to-note creates a linked note (HTML converted to readable text, review CR-1/CR-2) | `to-note creates a note with the mail's headers and text, linked to the mail`, `to-note keeps the block structure of an HTML part written without whitespace`, `to-note collapses the source indentation of a pretty-printed HTML part but keeps pre text`, `to-note separates headings, quotes, header cells and rules of an HTML part and drops trailing blanks`, new `to-note of indented HTML keeps nbsp, spaces inline elements once, keeps nested pre text and skips empty cells`, `inline link tokens in a mail's text do not link the note to other items`, `MailGtdMailServerTests` (502/503/404, NUL removal) | same | Pass |
| AC2: links survive moves (Message-ID re-resolution) | `links survive a move that gives the message a new row, re-resolved by Message-ID`, `links survive a move that keeps the row`, `waiting-for and note links survive a move that gives the message a new row`, `links keep resolving to the current copy across repeated moves`, `a moved message requested by its old id links its current copy`, `to-note by the old id of a moved message reads and links its current copy` | same | Pass |
| AC3: the mail.gtd test suite passes (Postgres through Testcontainers) | all 47 tests (`PostgresTestBase`) | same | Pass (MailGtdWebTests 42, MailGtdMailServerTests 5; 0 failures, 0 errors, 0 skipped) |
| AC4: OpenAPI regenerated and `OpenApiContractTest` passes | not in this test-writer scope (no API change since the regeneration) | `sh scripts/check.sh` runs the server `./gradlew check`, which includes `OpenApiContractTest` | Pass as part of the full check; the parent should still record its own explicit `./gradlew test --tests '*OpenApiContractTest'` run |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' jacocoTestReport ktlintCheck` (started 2026-10-21T06:30:27+02:00, finished 06:31:27+02:00)

Result:

Pass: BUILD SUCCESSFUL. `build/test-results/test/TEST-ch.sonensei.cockpit.mail.gtd.MailGtdWebTests.xml`: 42 tests, 0 failures/errors/skipped (includes the new test). `TEST-ch.sonensei.cockpit.mail.gtd.MailGtdMailServerTests.xml`: 5 tests, 0 failures/errors/skipped. ktlintCheck passed on main and test source sets.

## Full test execution

Command:

`sh scripts/check.sh` (server `./gradlew check`, web `npm run check`, android `ktlintCheck testDebugUnitTest lintDebug`), 2026-10-21T06:32:06+02:00 to 06:41:32+02:00

Result:

Pass: `== all checks passed`. The web build printed the existing initial-bundle budget warning (650.97 kB against 500 kB), not related to this task. The run took about 9.5 minutes on this machine, above the 2-minute guideline but under the 15-minute `done` timeout.

## .taskcheck

Result:

Pass (`.taskcheck` is `sh scripts/check.sh`, see above).

## Bugs found

None. The new edge-case test passed on the first run against commit 21955657.

Observation, not a defect and no failing test: `noteBody` trims the converted text, so if an HTML-only mail starts with a `<pre>` block, the leading indentation of its first line is lost. The other `pre` lines keep theirs. No requirement covers this. It is left for the parent to decide.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: the added lines in `git diff -U0 ce9e2a2d HEAD` for each file, intersected with JaCoCo XML `<line>` entries (executable lines) and counted as covered when `ci > 0`. MailGtdController.kt: 32/32 changed executable lines covered. MailGtdService.kt: 149/149 covered. For the last fix commit alone (`git diff -U0 b9f0a9d6 HEAD`), MailGtdService.kt has 34/34 changed executable lines covered. Changed-branch coverage was not derived from this.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-gtd; base ce9e2a2dea0ba45410863b86ed8559157e82345f (merge-base given by the parent); HEAD 1efb5405d25691ee7bdf5e3949804cb46d4f5ad1; task commits from `git log ce9e2a2d..HEAD`, all mail-gtd commits; file scope from `git diff --name-only ce9e2a2d..HEAD -- server/src/main web/src android/app/src/main`, matching the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 before and after the run were the same. MailGtdController.kt 1580681f3ec5fe7d2ae260bd74f959bca76da5b2b41ad3c6234d545869669eb3, MailGtdService.kt 627cd267ce5e85fbb09167f3adbedf9fc09197e894103dd3f51acb0d1a2529f6, MailGtdMailServerTests.kt 641e4fe28def59c09e3600a94d86f42e4581827e0d9877336c23a2dbe6c13cbe, MailGtdWebTests.kt (uncommitted, with the new test) 3e3158006a23e0952f6a4ec80544060f24f214966ec0b7d86346a301e35b667f.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt` and `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`. Excluded: `web/src/app/api/generated/**` (7 files, generated OpenAPI client, not hand-written behavior). Nothing was renamed or deleted, and no scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (version as set in `server/build.gradle.kts`, default tool version); `./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' jacocoTestReport ktlintCheck` in `server/`; selection: `ch.sonensei.cockpit.mail.gtd.*` only (MailGtdWebTests, MailGtdMailServerTests).
- Run start/completion time and exit status: 2026-10-21T06:30:27+02:00 to 06:31:27+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, mtime 2026-10-21 06:31:26.86 +0200, sha256 f3193893a5e20ac35d121314474c37a9c5ca3ccd84b97656d2d58136e079ca4b (unchanged after the later `scripts/check.sh` run, which does not run jacocoTestReport).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle reran `compileTestKotlin` and `test` for the changed test file, the report mtime falls inside the run window, and the snapshot hashes matched after the run.
- Final run after latest test additions and production fixes: yes (covers the latest production commit 21955657 and the new test).

Values below use covered/total (percentage) from JaCoCo counters (statements = INSTRUCTION).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt` | 157/157 (100%) | 2/2 (100%) | 12/12 (100%) | 32/32 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt` | 1368/1391 (98.3%) | 117/128 (91.4%) | 41/42 (97.6%) | 149/149 (100%) |
| Aggregate (summed counters; excludes the generated web client) | 1525/1548 (98.5%) | 119/130 (91.5%) | 53/54 (98.1%) | 181/181 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailGtdService.kt:233-234` (1 branch each) | `htmlText` visiting a node that is neither a TextNode nor an Element (a comment or DataNode) | Justified gap: the HTML comes from `HtmlMailSanitizer`, which removes comments, scripts and styles, so these nodes do not reach the converter. The `when` simply ignores them |
| `MailGtdService.kt:106, 122, 123, 126, 159` (1 branch each) | Kotlin null-safety fallbacks: note without title, message without folder, folder or account not found, `transactions.execute` returning null | Justified gap: defensive. The entities are non-null under the schema and the transaction always returns the created item |
| `MailGtdService.kt:277, 279` (2 branches each) | `display`/`full` with a null address name | Partly covered (blank name tested: `a blank display name falls back to the address in waitingForText`, `to-waiting ... carol@example.test`). The remaining missed bytecode branches come from `takeIf`/elvis variants and need no extra test |
| `MailGtdService.kt:164` (`getDATE_FORMAT`) | Kotlin-generated getter of a private companion constant | Non-material: the constant is used directly, and the `**Date:**` header line is tested |

## Unable to test

- Not tested: a `<pre>` as the first block of an HTML-only mail (see the observation under "Bugs found"). No requirement defines it, so no test asserts it.
- AC4 (OpenAPI regeneration) is the parent's to verify explicitly. It passed only as part of the full `scripts/check.sh` server check.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient and no production defects were found.
