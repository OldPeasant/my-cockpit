# Test Report

Date: 2026-10-21

Task: mail-gtd - Mail to GTD integration

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
