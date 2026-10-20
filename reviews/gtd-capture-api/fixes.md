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

---

# Fix round 2

Date: 2026-10-20

Task: gtd-capture-api - Quick capture endpoint and syntax parser

Scope: current task. This round covers the approved findings CR-6 and CR-7 from the re-review in `reviews/gtd-capture-api/review.md` (task base b977d6d6, reviewed HEAD 72b3b374). Changes are in `server/src/{main,test}/kotlin/ch/sonensei/cockpit/gtd/`, plus the regenerated `api/openapi.yaml` and `web/src/app/api/generated/`.

## Fixed findings

### CR-7 — The request schema does not describe how the title is chosen

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/CaptureDtos.kt`
- `api/openapi.yaml` (regenerated)
- `web/src/app/api/generated/models/capture-request.ts` (regenerated)
- `web/src/app/api/generated/models/capture-preview-response.ts` (regenerated)

Problem:
The `CaptureRequest` description said "The first line of `text` becomes the title". The parser uses the first non-blank line, and with `parse` a title line of only recognized tokens makes the next non-blank line the title (`CaptureParser.kt`, the `title.isEmpty() && cleaned != titleLine` branch). Neither schema mentioned this fallback.

Fix:
The `CaptureRequest` description now says the first non-blank line becomes the title, the capture syntax applies to the title line, and a title line of only recognized tokens makes the next non-blank line the (unparsed) title. The `CapturePreviewResponse` description has the same fallback sentence. Only descriptions changed, no field or type. I regenerated the contract and the web client. The diff contains only these description texts.

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass (it regenerated `api/openapi.yaml`)
- `cd web && npm run api:gen` — Pass (2 model doc comments changed)
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` (without the flag) — Pass (1 test, 0 failures)
- `npm --prefix web run check` — Pass (`api:check` "src/app/api/generated is up to date", lint, 757 unit tests, build)

## Not fixed

### CR-6 — A `null` element in `fileIds` gives a server error instead of a 400

Status:
Invalid (I added the regression test, but no production change was needed)

Reason:
The finding does not reproduce. This server uses Jackson 3 (`tools.jackson.module:jackson-module-kotlin`, Spring Boot 4.1.1). Its Kotlin module rejects the `null` element of the non-null `List<UUID>` during deserialization. `{"text":"x","fileIds":[null]}` therefore already gets a Problem Details 400 (`"detail":"Failed to read request"`, `application/problem+json`), and `CaptureRequest.toCommand()`/`CaptureService.capture` are never reached. I saw this with MockMvc `print()`.

I first added an `InvalidRequestException("fileIds", ...)` check to `toCommand()`. A test expecting its `errors[0].field = fileIds` failed, because the response comes from the deserializer and the check is never reached. I removed that check again, so it is not left as dead code. `CapturePreviewRequest` has no `fileIds`, so the preview endpoint needs no change. `TaskRequest.contextIds` was not touched (out of scope).

The `[null]` case is now in `capture rejects malformed bodies` (`server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureEdgeCaseWebTests.kt`). It asserts 400 with `application/problem+json`, so a future change to the Jackson null handling that brings back a 500 will fail the test. The existing `taskCount() shouldBe 0` also covers it.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' ktlintCheck` — BUILD SUCCESSFUL (317 tests, 0 failures, 0 skipped)
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` (without `UPDATE_OPENAPI`) — BUILD SUCCESSFUL (1 test, 0 failures)

Full test suite:
- Not run in this round. The change is description text plus one test assertion. The parent runs `.taskcheck`.

Lint/type-check/build:
- Server ktlint — Pass
- `npm --prefix web run check` — Pass. The existing "bundle initial exceeded maximum budget" warning is not from this change.

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0 (CR-6 is invalid as reported and is covered by a regression test; CR-5 remains recorded as an assumption, see round 1)

## Final status (round 2)

All validated findings fixed and validation passed.
