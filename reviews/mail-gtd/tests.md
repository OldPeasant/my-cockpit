# Test Report

Date: 2026-10-21

Task: mail-gtd - Mail to GTD integration

Scope: task commits after the immutable base 4e2c9b7834f3d24c1f6cee61d97ace9b50071c6b up to HEAD f44fb0d9ec50d95cf00e64703f8b15ec115bdf20 on branch main. The task commits are d6802d66, 4a0c4dc2, 3a2ae031, 3ae6bf4f, 3abab30c, 0c3927e5 and f44fb0d9; the commits in between are other agents' heartbeat, claim and release commits. The parent rebased while this run was in progress (old HEAD 3b9dc8dd). `git diff 3b9dc8dd f44fb0d9 -- server/src/{main,test}/kotlin/ch/sonensei/cockpit/mail/gtd` is empty, so the code under test is identical on both revisions. The production code is `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/` (the only production files in `git diff 4e2c9b78..HEAD -- server/src/main`). The generated `api/openapi.yaml` and web client are out of scope for unit coverage. The only uncommitted input is the test change listed below.

Requirements:
- `tasks/mail-gtd.md` acceptance criteria and recorded assumptions
- SPEC.md §7.3 (via the task), `docs/ARCHITECTURE.md` "Coverage"
- `reviews/mail-gtd/fixes.md` (CR-1 and CR-2 fixes, used to target regression tests)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`

## Tests added or updated

Count: 3 (added)

- `waiting-for and note links survive a move that gives the message a new row`: the move-survival criterion was tested only for to-task links. This test covers the to-waiting and to-note links: after the original row is detached and a copy appears in Archive, each link is still available and its deep link points to the copy.
- `links keep resolving to the current copy across repeated moves`: covers a link made before the first move and a link made after it (that one points at the first copy). After a second move into a role-less folder, both links resolve to the second copy through Message-ID.
- `to-note separates headings, quotes, header cells and rules of an HTML part and drops trailing blanks`: a CR-1 regression test for the block elements that the fix lists but the earlier test did not use (`h1`, `blockquote`, `th`, `hr`), and for trailing spaces before a `br` line break.

The existing 42 tests already covered every other criterion in detail. Examples: to-task, to-waiting and to-note links seen from both ends; the recipient of a sent mail (Sent folder, account address in any letter case, identity, To then Cc then Bcc); a received mail waiting for its sender; a stale id linking the current copy; 404/400/403 responses that create nothing; and 502/503 responses from the mail server.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: to-task creates a linked Inbox task | `to-task creates an Inbox task linked both ways to the mail`, `a repeated request creates another linked item`, `the response names the link that the links API returns`, title/limit tests | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*'` | Pass |
| AC1: to-waiting creates a linked Waiting For task; a sent mail waits for its recipient | `to-waiting from a sent mail waits for its recipients since the mail's date`, `a mail from one of the account's identities counts as sent outside the Sent folder`, `sent detection by the account address ignores case, outside the Sent folder`, `to-waiting from a sent mail without To waits for Cc, and without To and Cc for Bcc`, `to-waiting from a sent mail with To ignores Cc and Bcc`, `to-waiting from a received mail waits for its sender` (assumption), waitingSince tests | same | Pass |
| AC1: to-note creates a linked note | `to-note creates a note with the mail's headers and text, linked to the mail`, HTML/plain tests, the new HTML structure test, `inline link tokens in a mail's text do not link the note to other items`, `MailGtdMailServerTests` (502/503/404, NUL removal) | same | Pass |
| AC2: links survive moves (Message-ID re-resolution) | `links survive a move that gives the message a new row, re-resolved by Message-ID`, `links survive a move that keeps the row`, new `waiting-for and note links survive a move that gives the message a new row`, new `links keep resolving to the current copy across repeated moves`, `a moved message requested by its old id links its current copy`, `to-note by the old id of a moved message reads and links its current copy` | same | Pass |
| AC3: the mail.gtd test suite passes (Postgres through Testcontainers) | all 45 tests (`PostgresTestBase`) | same | Pass (MailGtdWebTests 40, MailGtdMailServerTests 5; 0 failures, 0 skipped) |
| AC4: OpenAPI regenerated and `OpenApiContractTest` passes | not in this test-writer scope | none | Not run: the parent verifies it with `./gradlew test --tests '*OpenApiContractTest'` |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' jacocoTestReport ktlintCheck`

Result:

Pass (BUILD SUCCESSFUL; 45 tests, 0 failures, 0 errors, 0 skipped; ktlintCheck passes). `ktlintFormat` was run once before this and changed nothing outside the edited test file.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run. The parent said not to run the full server check because it is slow on this machine. `reviews/mail-gtd/fixes.md` records an earlier full run.

## .taskcheck

Result:

Not run (same reason; the parent runs it before `done`).

## Bugs found

None.

## Coverage

Status: verified (fresh focused run, exit 0)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate metric. Both files are new in this task (398 added lines, nothing removed), so whole-file coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-gtd; base 4e2c9b7834f3d24c1f6cee61d97ace9b50071c6b (from the parent); measured HEAD f44fb0d9ec50d95cf00e64703f8b15ec115bdf20; scope from `git diff --stat 4e2c9b78..HEAD -- server/src/main` and the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): SHA-256 MailGtdController.kt 1580681f…cbe, MailGtdService.kt f7c44185…450d, MailGtdWebTests.kt (uncommitted, with the new tests) 833ec8a3…203, MailGtdMailServerTests.kt 641e4fe2…cbe. All four hashes were the same before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt` and `MailGtdService.kt`. No renames or deletions. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/` (generated).
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (project default tool version, no override in `server/build.gradle.kts`); `./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' jacocoTestReport ktlintCheck`; working directory `server/`; selection `ch.sonensei.cockpit.mail.gtd.*` (45 tests).
- Run start/completion time and exit status: tests started 2026-10-21T04:07:38Z; report written 2026-10-21T06:08:12+02:00; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, SHA-256 731d5f81b543d31b3cb3340269406ebd9f227c241f8ac2c5dbc133b1c514999c.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test task re-executed because the test sources changed, the report timestamp is from this run, and the snapshot hashes did not change.
- Final run after latest test additions and production fixes: yes. The CR-1 and CR-2 fixes are in 3abab30c, the new tests were included, and the production code is unchanged since then.

Values below use covered/total (percentage); JaCoCo has no separate statement counter, so instructions are shown in its place.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt` | 157/157 (100%) | 2/2 (100%) | 12/12 (100%) | 32/32 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt` | 1256/1279 (98.2%) | 103/114 (90.4%) | 37/38 (97.4%) | 119/119 (100%) |
| Aggregate (summed counters, both files; no missing or unmeasured files) | 1413/1436 (98.4%) | 105/116 (90.5%) | 49/50 (98.0%) | 151/151 (100%) |

The numbers are the same as in a run made before the new tests. The new tests add behavior assertions on paths that other tests already execute: links of the other item types, repeated moves, and more HTML elements.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailGtdService.kt:106` (`note.title ?: ""`) | Null title fallback | Unreachable: `titleOf` always returns a non-empty title. Defensive; no test. |
| `MailGtdService.kt:122-123` (`folderId` null / folder row missing) | Sent detection for a message without a folder | Unreachable: `locator.current` returns only located messages, and a located message always has a folder (`requireLocation`). No test. |
| `MailGtdService.kt:126` (account row missing) | Account lookup returns null | Unreachable: the message's account FK exists inside the transaction. Defensive; no test. |
| `MailGtdService.kt:159` (`checkNotNull` failure) | `TransactionTemplate.execute` returning null | Unreachable: the blocks return non-null values. No test. |
| `MailGtdService.kt:164` (2 instructions) and the 1 missed method | Kotlin companion object synthetic code | Bytecode artifact; nothing to test. |
| `MailGtdService.kt:199-200` (1 branch each) | `when` type checks for nodes that are neither text nor elements (comments, data nodes) | The sanitized HTML from the cache gives no such nodes in the tests. Low risk: they are ignored either way. No test. |
| `MailGtdService.kt:239,241` (2 branches each) | Kotlin null-safe chain branches in `display`/`full` | Null and blank names are tested (`a blank display name falls back…`, addresses without names in headers). The remaining branches are compiler-generated. No test. |

## Unable to test

- AC4 (OpenAPI regeneration and contract test) is outside this helper's scope; the parent verifies it.
- A NUL character in the subject (`titleOf` strips `\u0000`) cannot be set up through the cache: PostgreSQL `text` rejects NUL, so a stored subject cannot contain one. No follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 3 tests were added (move survival for waiting-for and note links, resolution across repeated moves, CR-1 HTML block elements), and the focused suite (45 tests) and ktlintCheck pass.
