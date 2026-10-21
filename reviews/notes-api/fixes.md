# Fix Report

Date: 2026-10-21

Task: notes-api - Notes API

Scope: current task. These are the findings CR-1, CR-2 and CR-3 from `reviews/notes-api/review.md`. They were fixed on top of task commits d0c975a2, 26cb9c83 and 5ddbfa9e (base 66eba242).

## Fixed findings

### CR-1 - Concurrent content updates with the same ETag fail with 500 instead of 409

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt`

Problem:
Two body changes of the same note version could run at the same time, and both could pass `checkVersion`. Both then computed the same revision number. Hibernate flushes inserts before updates, so the second transaction's `INSERT note_revision` failed first, on the unique constraint `note_revision_number`. That gave a `DataIntegrityViolationException` and a 500. The versioned `UPDATE note` never ran, so the documented 409 never came.

Fix:
`keepRevision` now flushes the changed note (`notes.saveAndFlush(note)`) before it saves the revision. The versioned update runs first. In the second transaction it waits for the first transaction's row lock and then matches 0 rows, which gives an `ObjectOptimisticLockingFailureException`. The existing `ProblemDetailsAdvice` maps that to 409. `restoreRevision` now sets the restored title and body before it calls `keepRevision`, as `update` already did. It still reads `savedAt` before the change. The KDoc describes the new order. The fix stays inside the notes module, and the global advice is unchanged.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' ktlintCheck` - Pass. NoteInfraTests 9/9 pass, including "two concurrent body changes of the same version keep one change and at most one revision", which failed before this fix.

### CR-2 - `archived` list parameter has no enum in the API contract

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/notes/api/NoteController.kt`
- `api/openapi.yaml` (regenerated)
- `web/src/app/api/generated/fn/notes/list-notes.ts` (regenerated)

Problem:
`archived` accepts only `true`, `false` or `all`, but the contract and the web client typed it as a free string.

Fix:
Added `schema = Schema(type = "string", allowableValues = ["true", "false", "all"])` to the `@Parameter`. Then regenerated `api/openapi.yaml` with `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`, which added an enum to the parameter, and the web client with `npm run api:gen`, which now gives `archived?: 'true' | 'false' | 'all'`. The server-side validation is unchanged.

Validation:
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` (without the flag) - Pass
- `cd web && npm run check` - Pass

### CR-3 - Search indexing of a note is duplicated, and `NoteService.changed` KDoc names a caller that does not use it

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/notes/infra/NoteTrashContributor.kt`

Problem:
`NoteTrashContributor.restore` and `NoteService.changed` each built the note's search document. The KDoc of `changed` claimed it was used after a Trash restore, which was not true.

Fix:
Added a top-level extension `SearchIndexer.indexNote(note)` in the notes domain. It is now the only place that decides what a note's search document holds (display title plus the Markdown-stripped body). `NoteService.changed` and `NoteTrashContributor.restore` both use it. `changed` is now private, and its KDoc is corrected. An extension function was used instead of injecting `NoteService` into the contributor, so no new bean dependency was added. The restore path still does not publish a ChangeEvent, because `TrashService` publishes it.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` - Pass. This covers the NoteInfraTests Trash restore re-index tests.

## Not fixed

None of the supplied findings remain unfixed.

These are new observations outside the supplied findings, reported to the parent and not changed:
- Android lint fails on the generated client: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` fails with 2 `WrongCommentType` errors. They are in `app/build/generated/openapi/.../models/NoteImageResponse.kt:48` and `NoteResponse.kt:52`, which are generated from the descriptions of `NoteImageResponse.markdown` and `NoteResponse.bodyMd`. Those descriptions contain `![](cockpit-file:{fileId})` / `[[type:id]]` and come from task commit 26cb9c83. CR-2 did not cause this, because it only changes a query parameter. This task's API descriptions cause it, and `.taskcheck` will fail on machines that have the Android SDK. The parent should decide whether to reword those descriptions in `NoteDtos.kt`.
- `ClockConfigurationTests` "the application clock is the system clock in UTC" (core, from task p0-db-conventions) failed in both full server runs. It compares a microsecond-truncated `clock.instant()` with a nanosecond `Instant.now()` taken before it, so it is timing dependent. It passes when run alone. It is not related to notes.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' ktlintCheck` - BUILD SUCCESSFUL: NoteMarkdownTests 8, NoteWebTests 20, NoteInfraTests 9, NoteImageMaxSizeWebTests 1, NoteEdgeCaseWebTests 26, OpenApiContractTest 1, with 0 failures.
- The parent asked for `./gradlew detekt`, but that task does not exist in this project ("Task 'detekt' not found"). ktlintCheck was run instead.

Full test suite:
- `sh scripts/check.sh` ran twice. The server part reported 2297 tests and 1 failure both times. That failure was `ClockConfigurationTests` "the application clock is the system clock in UTC", which is not related to notes (see above). `./gradlew test --tests '*ClockConfigurationTests'` passes when run alone.

Lint/type-check/build:
- `cd web && npm run check` - Pass
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - Fail (2 WrongCommentType errors in generated notes models, see above)

`.taskcheck`:
- Fail. It failed in the server step because of `ClockConfigurationTests`, which is outside this task. On machines with the Android SDK, the Android lint failure above would also block it.

Remaining unresolved findings:
0

## Final status

Fixes completed, but validation still fails.

---

# Fix Report (round 3)

Date: 2026-10-21

Task: notes-api - Notes API

Scope: current task. This round covers finding CR-2 from the current `reviews/notes-api/review.md` (the review of 19eeb0b9). The fixes are on top of task commits d9341538, 07e336b2 and 19eeb0b9 (base 00b53719). The earlier sections above are from previous rounds and still apply to their own findings.

## Fixed findings

### CR-2 - NUL characters in tags give 500 instead of 400

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteEdgeCaseWebTests.kt`

Problem:
`validate` rejected NUL in `title` and `bodyMd`, but `normalizeTags` did not check tags. A tag containing `'\u0000'` reached PostgreSQL (in the `findTagSpelling` native query or the `note_tag` insert). PostgreSQL rejects 0x00 in `text`, so the request failed with a generic 500. The `GET /api/v1/notes?tag=...` filter had the same problem through the tag predicate.

Fix:
- `normalizeTags` now adds `FieldError("tags", "must not contain NUL characters")`, collected together with the other tag errors, the same way title and body NUL is rejected. It runs before any query, so create and PATCH return 400 and change nothing.
- `NoteService.list` throws `InvalidRequestException("tag", "must not contain NUL characters")` when the tag filter contains NUL, before the query is built. The existing `ProblemDetailsAdvice` maps it to a 400 Problem Details response with an `errors` entry for `tag`. The check is in the service, next to the other tag handling, so the controller is unchanged. KDoc of both functions now names the new 400 case.
- New web tests in `NoteEdgeCaseWebTests`:
  - "tags with NUL characters are rejected with 400 on create and PATCH": checks that a POST returns 400 `application/problem+json` with field `tags` and that no note is created, then that a PATCH returns 400 with field `tags` and the tags stay unchanged.
  - "a tag filter with NUL characters is rejected with 400": checks that `GET /api/v1/notes?tag=ho%00me` (the NUL is passed as a URI variable, so it is sent as `%00`) returns 400 `application/problem+json` with field `tag`, and that a normal tag filter still works.

The API contract did not change: `OpenApiContractTest` passes without regeneration, so `api/openapi.yaml` and the generated clients were not touched.

Validation:
- `cd server && ./gradlew ktlintFormat` - no changes needed after the edit
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest'` - BUILD SUCCESSFUL. NoteEdgeCaseWebTests 30 (both new tests included), NoteWebTests 20, NoteInfraTests 9, NoteRevisionInfraTests 5, NoteMarkdownTests 8, NoteMigrationTests 3, NoteImageMaxSizeWebTests 1, OpenApiContractTest 1, with 0 failures and 0 errors.
- `cd server && ./gradlew ktlintCheck` - Pass

## Not fixed

### CR-1 - Recorded evidence and `quality.json` name revisions that no longer exist

Status:
Out of scope

Reason:
This finding is about TFP evidence (`quality.json` `reviewed_revision` and `tasks.py verify` entries). The parent worker owns those, and this fixer was asked to fix only CR-2. No code change is needed for it. Because this round changes code, the parent must re-run the helpers on the final code and then update `reviewed_revision` and the verify entries.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest'` - Pass (77 tests, 0 failures)
- `cd server && ./gradlew ktlintCheck` - Pass

Full test suite:
- `sh scripts/check.sh` - the tfp gate tests passed (20). The server step ran 2723 tests with 1 failure: `ClockConfigurationTests` "the application clock is the system clock in UTC" (core, not notes). The message was `2026-10-21T02:23:28.045783Z should be >= 2026-10-21T02:23:28.045783187Z`. This is the same timing-dependent failure recorded in the earlier round (it compares a microsecond-truncated clock with a nanosecond `Instant.now()`). It is not related to this change. The server step took 11m 52s on this machine.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` - Pass. The web and Android parts were not affected (no contract change).

`.taskcheck`:
- Fail, only because of the unrelated `ClockConfigurationTests` failure above.

Remaining unresolved findings:
0 in the supplied scope (CR-2 fixed). CR-1 is left to the parent worker.

## Final status

Fixes completed, but validation still fails (only the unrelated, pre-existing `ClockConfigurationTests` timing failure in `.taskcheck`).
