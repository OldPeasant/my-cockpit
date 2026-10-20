# Fix Report

Date: 2026-10-20

Task: gtd-capture-api - Quick capture endpoint and syntax parser

Scope: current task; approved findings CR-1 to CR-5 from `reviews/gtd-capture-api/review.md` (task base 5ce6b358, code commits 66f0b4dd and 73f8e701). Changes are limited to `server/src/{main,test}/kotlin/ch/sonensei/cockpit/gtd/`.

## Fixed findings

### CR-1 — Body lines of the captured text are rewritten (whitespace collapsed, also with `parse: false`)

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureParserTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureWebTests.kt`

Problem:
`splitTitle` collapsed spaces and tabs and trimmed every line, so indented Markdown, nested lists and tabs in the notes were changed. `plain()` (`parse: false`) did the same.

Fix:
`splitTitle` is now split into `splitLines` (the first non-blank line, plus the lines after it kept verbatim, only leading and trailing blank lines dropped), `normalizeLine` (collapses whitespace on the title line only) and `cutTitle` (the 500-character cut, unchanged). `parse` and `plain` both use them. Line breaks are still joined with `\n`, so CRLF becomes LF. The KDoc says so.

Validation:
- New parser test `body lines keep their indentation, tabs and inner blank lines` (covers `parse` and `plain`) — Pass
- New web test `body lines are stored verbatim, with and without parsing` (`notesMd` through `POST /capture`, `parse` true and false) — Pass
- The existing test `the first non-blank line is the title, the rest extra notes` now expects the trailing spaces of a body line to stay — Pass

### CR-2 — Capture tokens were interpreted and removed in the body lines

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureParserTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureWebTests.kt`

Problem:
`TOKEN.replace` ran over the whole multi-line text, so `^1`, `@home`, `!today` and similar text on later lines changed the list, the due date or waiting-for, and was removed from the notes.

Fix:
The text is split first, and tokens are parsed only in the title line (the first non-blank line). The remaining lines pass through unchanged. The KDoc of `ParsedCapture` and `CaptureParser` now says this. Edge case: before this fix, a text like `"#garden !today\nPlant tulips"` used `Plant tulips` as the title, because the token-only first line became blank. To keep that behavior, when the title line contains only recognized tokens, the next non-blank line becomes the title. That line is not parsed. A single line of only tokens still gives an empty title, which is a 400.

Validation:
- New parser test `tokens on later lines are not parsed and stay in the notes` — Pass
- New parser test `a title line of only tokens takes the next line as the title, unparsed` — Pass
- Web test `body lines are stored verbatim, with and without parsing` (`^1`, `!today`, `@home` in the body: list stays INBOX, no due date, tokens kept in `notesMd`) — Pass
- The existing `invalid captures are rejected` (`"!today ^waiting"` gives 400) — Pass

### CR-3 — The concurrent duplicate-id path of `POST /capture` was untested

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureWebTests.kt`

Problem:
No test covered the `catch (DataIntegrityViolationException)` fallback in `CaptureController.capture`.

Fix:
New Testcontainers test `a concurrent capture with the same id returns 200 with the winner's task and links no files again`. A real race is forced deterministically, using the same approach as `TaskPositionAndCursorTests`. A background thread runs a real `CaptureService.capture` (same id, one file) inside a `TransactionTemplate` and holds the transaction uncommitted. The HTTP `POST /capture` then passes `existingCreate` (it sees no row), and its `insert into task` blocks on the row lock. The background thread polls `pg_stat_activity` on a separate connection until it sees that lock wait, and only then commits. The test asserts: the competitor's capture reports `created = true` (the 201 side), the HTTP capture answers 200 with the winner's id and title, there is exactly one task row, and the task has exactly one file link. The HTTP capture's link insert rolled back with its transaction. No production change was needed.

Note: the first version of the test polled `pg_stat_activity` inside the competitor's transaction. Postgres serves that view from a snapshot that is fixed for the whole transaction, so the poll never saw the lock wait. Polling on a connection of its own fixed this.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.gtd.Capture*'` — Pass (the race test takes about 0.1 s)
- Mutation check: I temporarily replaced the fallback with `throw ex`. The test then failed with `Status expected:<200> but was:<500>`, which shows it really goes through the fallback branch. I reverted the mutation, and `git diff` shows no change to `CaptureController.kt`.

### CR-4 — Trailing punctuation was removed together with a recognized token

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureParserTests.kt`

Problem:
A recognized token was replaced with `""` together with its trailing `.,;:!?)`.

Fix:
The token regex now captures the whitespace before the token, instead of using a lookbehind. A recognized token with trailing punctuation is replaced by that punctuation, and the whitespace before the token is dropped. Example: `"Call mom @phone, then"` becomes `"Call mom, then"`, and `"(for #garden)"` becomes `"(for)"`. Without punctuation, the token is replaced by the whitespace before it. Unrecognized tokens stay unchanged. The URL and email cases still pass.

Validation:
- Renamed and adjusted test `trailing punctuation is not part of a token and stays in the title` (`"Call mom, tomorrow is due."`, `"Buy tiles (for) today"`) — Pass
- `tokens need whitespace before them, so urls and emails are untouched` — Pass

## Not fixed

### CR-5 — `!today`/`!tomorrow` in an offline capture resolve to the upload date

Status:
Out of scope (skipped by the parent's decision)

Reason:
The parent worker decided not to fix this in code. The behavior is recorded as an assumption in `tasks/gtd-capture-api.md`: relative dates are resolved with the server clock, and `and-capture-queue` may resolve them on the client before queueing. No code change.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass (no further changes)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.Capture*'` — Pass (CaptureParserTests + CaptureWebTests)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` — Pass (261 tests, 0 failures, 0 skipped)

Full test suite:
- `cd server && ./gradlew check` — BUILD SUCCESSFUL (ktlint + 1894 tests, 0 failures, 0 skipped)

Lint/type-check/build:
- Covered by `./gradlew check` (ktlint, Kotlin compile) and `.taskcheck`

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed": server, web, android)

Remaining unresolved findings:
0 (CR-5 was skipped by the parent's decision and is recorded as an assumption)

Contract: no request or response field changed, so `api/openapi.yaml` and the web client were not regenerated. The schema description of `CapturePreviewResponse` (`server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/CaptureDtos.kt`, which is generated into `api/openapi.yaml`) still says a token "starts the text or follows whitespace". It does not mention that only the title line is parsed. That is a documentation-only change in the generated contract, outside the folders this fixer was allowed to touch. The parent worker can decide on it.

## Final status

All validated findings fixed and validation passed.
