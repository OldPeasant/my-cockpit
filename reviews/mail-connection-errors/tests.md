# Test Report

Date: 2026-10-26

Task: mail-connection-errors - Shared MailConnectionException handler for mail controllers

Scope: base 9468e8e0454ca451fe469edeb5d4ea50ef4d106a (supplied by the parent), measured HEAD
d5e342f9fde71dfc05dfa7b658b02150c7f71d59 on `main`, clean working tree. Task code commits (supplied by
the parent): af3c5ccb (production), b5ea3a54 and 953306f0 (tests). d5e342f9 only adds `reviews/`.
Production changes (`git diff --numstat 9468e8e0 HEAD -- server/src/main`):

- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailConnectionAdvice.kt` (new, +36)
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt` (-14, deletions only)
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftController.kt` (-13, deletions only)
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt` (-19, deletions only)
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountController.kt` (-16, deletions only)

Requirements:
- `tasks/mail-connection-errors.md` acceptance criteria and notes (assumptions: DraftController copy removed
  too; advice uses `basePackages = ch.sonensei.cockpit.mail` and `HIGHEST_PRECEDENCE`)
- `AGENTS.md` (no secrets or server responses in INFO logs, fictitious test data), `docs/ARCHITECTURE.md` "Coverage"
- The removed per-controller handler bodies (from `git diff 9468e8e0 af3c5ccb`) as the reference for
  "response unchanged": the advice body is identical to each removed copy.

## Test files changed

None by this test-writer run. The task's own commits already contain the tests reviewed here:

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailConnectionAdviceWebTests.kt` (added in 953306f0)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailMessageControllerTests.kt` (b5ea3a54: registers the advice)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftControllerTests.kt` (b5ea3a54: registers the advice)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailSecretRedactionTests.kt` (b5ea3a54: logger and handler of the advice)

## Tests added or updated

Count: 0

I checked the existing tests against everything the parent listed as possibly missing. All of it is
already covered, so new tests would only duplicate them:

- POOL_EXHAUSTED -> 503 vs every other `MailConnectionError` -> 502: `MailConnectionAdviceWebTests` loops over
  all `MailConnectionError.entries`, for each of the four controllers, through the real Spring context
  (`@SpringBootTest` + MockMvc + security with CSRF).
- Problem Details fields: status, title ("Service Unavailable" / "Bad Gateway"), type (default `about:blank`,
  left out of the JSON), detail (`error.message`), `errorCode` (`error.name`), content type `application/problem+json`.
- Precedence over the global `ProblemDetailsAdvice`: in the full application context a MailConnectionException
  answers 502/503 and not the global 500 catch-all. Other exceptions (NotFoundException -> 404,
  IllegalStateException -> 500 "An unexpected error occurred") still reach the global advice and carry no `errorCode`.
- Redaction: the response body never contains the fictitious server response of the cause (web test), and the
  INFO log of `MailConnectionAdvice` contains no server response (`MailSecretRedactionTests`).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: a single MailConnectionException handler serves all mail controllers | `MailConnectionAdviceWebTests`: `an exhausted pool answers 503 POOL_EXHAUSTED from every mail controller`, `every other mail server failure answers 502 with its error code from every mail controller, not the 500 catch-all` (MailMessageController, MailAccountController, MailGtdController, DraftController) | `cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.*' jacocoTestReport` | Pass |
| AC1: the per-controller copies are gone | Static check: `grep -rn "ExceptionHandler(MailConnectionException" server/src/main` | same as left | Pass: only match is `mail/api/MailConnectionAdvice.kt:26` |
| AC2: response status unchanged (503 for POOL_EXHAUSTED, 502 otherwise) | the two web tests above; `MailSecretRedactionTests`: `an exhausted pool answers 503`, `a connection failure answers 502 with a fixed message and logs no server response at INFO` | mail test command above | Pass |
| AC2: Problem Details type/title/detail (and `errorCode`) unchanged | web tests above (`shouldBeMailProblem`); the advice body equals the removed handler body line by line (`git diff 9468e8e0 af3c5ccb`) | mail test command above | Pass |
| AC2: the shared advice wins over the global ProblemDetailsAdvice and leaves other exceptions to it | `every other mail server failure ... not the 500 catch-all`, `other exceptions of a mail controller still reach the global advice` | mail test command above | Pass |
| AC2: existing mail controller tests pass | the whole `ch.sonensei.cockpit.mail.*` suite: 1299 tests, 0 failures, 0 errors, 0 skipped | mail test command above | Pass |
| Project rule: no server responses or secrets in INFO logs or the response body | `a connection failure answers 502 with a fixed message and logs no server response at INFO`; body `shouldNotContain "fictitious-server-secret"` in the 502 web test | mail test command above | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.*' jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL in 2m 53s. 1299 tests, 0 failures, 0 errors, 0 skipped (summed from
`server/build/test-results/test/*.xml`). `MailConnectionAdviceWebTests`: 3 tests passed;
`MailSecretRedactionTests`: 4 tests passed.

## Full test execution

Command:

`sh scripts/check.sh` (the `.taskcheck`: lint + unit tests of all parts)

Result:

Fail: 1 unrelated flaky calendar test (it passes when run alone); details under ".taskcheck" below.

## .taskcheck

Result:

Fail (unrelated to this task), run once on HEAD d5e342f9 (`sh scripts/check.sh`, started 2026-10-26 ~03:49+01:00):

- tfp completion gate tests: 20 tests OK.
- server `./gradlew --quiet check`: 4592 tests completed, 1 failed, BUILD FAILED in 19m 35s. The failure is
  `ch.sonensei.cockpit.calendar.infra.ics.IcsFeedClientTests` > `a feed of exactly the maximum size is read, a larger
  Content-Length is refused before reading` (IcsFeedClientTests.kt:281): expected a message containing "larger than",
  got "The feed server could not be reached (IOException)". This is the calendar ICS client, which the task does not
  touch (no file under `calendar/` changed since the base). Rerun alone:
  `cd server && ./gradlew --quiet test --rerun --tests 'ch.sonensei.cockpit.calendar.infra.ics.IcsFeedClientTests'`
  -> 44 tests, 0 failures. So it looks like a timing-dependent (flaky) test under full-suite load, not a defect of
  this task.
- web and android: not run, because `check.sh` stops at the first failing part (`set -eu`). The task changes no
  web or android files.
- Note: the server check took 19.5 min, far above the "< 2 min" target and above the 15 min `TASK_CHECK_TIMEOUT` of
  `tasks.py done`. The parent should rerun `.taskcheck` and watch for this timeout.

## Bugs found

None.

## Coverage

Status: verified for the whole task scope (5 of 5 production files measured).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 8/8 executable lines (100%), 2/2 branches. Method: the task's only added production lines
are the new file `MailConnectionAdvice.kt` (`git diff --numstat`: +36 there, the four controllers have deletions only,
so they have no added lines to measure). The changed-line set is therefore all executable lines of
`MailConnectionAdvice.kt` in the JaCoCo report.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-connection-errors`; base
  9468e8e0454ca451fe469edeb5d4ea50ef4d106a and commits af3c5ccb, b5ea3a54, 953306f0 supplied by the parent;
  HEAD d5e342f9fde71dfc05dfa7b658b02150c7f71d59; file scope from `git diff --name-only 9468e8e0..HEAD -- server/src/main`,
  which matches the production commit af3c5ccb.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d5e342f9, clean working tree (no uncommitted
  or untracked inputs). sha256 `MailConnectionAdvice.kt` b949bc7e8aa865d6d403a3d170d9bd19aaeb515c2f28b591b9b2e8463077984c
  and `MailConnectionAdviceWebTests.kt` cb6e28f7bf19401a092466d27c668c05e7c71a35f35b2a7cb6ab94cb920bf68b, identical
  before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: the five files listed under Scope. No renames,
  deletions or exclusions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle 9.8.1);
  `./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.*' jacocoTestReport` in `server/`; selection: all
  `ch.sonensei.cockpit.mail.*` tests (`--rerun` forces a fresh test execution and a fresh `test.exec`).
- Run start/completion time and exit status: started 2026-10-26T03:45+01:00, report written 2026-10-26T03:48:06+01:00;
  BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 0ce0c93d98f109b73bf251a2cf601a97641c0b12f3dce37e80292b6febbc44a7.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test task executed (not up to date), the test
  result XML timestamps are 02:45-02:47Z (= 03:45-03:47+01:00) of this run, the report mtime follows them, and the
  source hashes were unchanged after the run.
- Final run after latest test additions and production fixes: yes. No tests were added by this run and no
  production fixes exist after HEAD d5e342f9.

Values below use covered/total (percentage) from the per-sourcefile counters of the JaCoCo XML. Statements = JaCoCo
INSTRUCTION; zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailConnectionAdvice.kt` | 55/55 (100%) | 2/2 (100%) | 2/2 (100%) | 8/8 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt` | 931/969 (96.1%) | 16/20 (80.0%) | 83/84 (98.8%) | 180/180 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftController.kt` | 1141/1239 (92.1%) | 66/76 (86.8%) | 59/74 (79.7%) | 168/170 (98.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt` | 105/105 (100%) | N/A (0 branches) | 11/11 (100%) | 25/25 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountController.kt` | 848/962 (88.1%) | 18/22 (81.8%) | 69/94 (73.4%) | 206/211 (97.6%) |
| Aggregate (summed counters, all 5 files, nothing excluded or unmeasured) | 3080/3330 (92.5%) | 102/120 (85.0%) | 224/265 (84.5%) | 587/594 (98.8%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailAccountController.kt`: lines 235, 294, 301, 391, 443; partial branches at 365, 367, 510, 511 | Pre-existing controller code; the task only deleted the handler from this file | Not task scope (no added lines in this file); no test added |
| `DraftController.kt`: lines 160-161; partial branches at 84, 141, 270, 426, 431, 515, 517 | Pre-existing controller code; the task only deleted the handler | Not task scope; no test added |
| `MailMessageController.kt`: partial branches at 367, 390, 423, 424 | Pre-existing controller code; the task only deleted the handler | Not task scope; no test added |
| `MailConnectionAdvice.kt` | The whole task behavior | Fully covered (no gaps) |

The missing functions in the controllers are most likely Kotlin-generated members (default-argument
bridges, data/companion helpers) of existing code; they are unrelated to the removed handlers.

## Unable to test

- The DEBUG log of the cause (`log.debug("Mail server failure", e.cause)`) is executed (line covered) but its
  content is not asserted. This is intended: DEBUG may hold server responses by design. No follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The existing tests from the task
commits already cover the status mapping, the Problem Details fields, the precedence over the global advice and
the redaction across all four mail controllers, so no tests were added.
