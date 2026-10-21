# Fix Report

Date: 2026-10-21

Task: gtd-convert-note - Convert task to reference note

Scope: current task. The findings come from `reviews/gtd-convert-note/tests.md` (TW-1, the failing `.taskcheck` web step) and `reviews/gtd-convert-note/review.md` (CR-1). Examined at HEAD 956fc38a on `main`.

## Fixed findings

### TW-1 - Generated web API client out of date with `api/openapi.yaml`

Files changed:
- `web/src/app/api/generated/fn/tasks/convert-task-to-note.ts` (new, generated)
- `web/src/app/api/generated/functions.ts` (generated, 2 export lines added)
- `web/src/app/api/generated/index.ts` (generated, 2 export lines added)

Problem:
The task added `POST /api/v1/tasks/{id}/convert-to-note` (operationId `convertTaskToNote`) to `api/openapi.yaml`, but `web/src/app/api/generated/` was not regenerated. `npm run api:check` in the web step of `sh scripts/check.sh` failed with "missing: fn/tasks/convert-task-to-note.ts, changed: functions.ts, changed: index.ts".

Fix:
Regenerated the client as `api/README.md` prescribes (`cd web && npm run api:gen`). No hand edits. `git status` afterwards shows exactly the three expected files changed and nothing else. The generated function posts to `/api/v1/tasks/{id}/convert-to-note` with an optional `If-Match` header and returns `NoteResponse`, which matches the contract.

Validation:
- `cd web && npm run api:gen` - Pass (only the 3 expected files changed)
- `cd web && npm run check` - Pass (api:check "src/app/api/generated is up to date", lint "All files pass linting", e2e typecheck ok, 79 test files / 1435 tests passed, `ng build` ok)

### CR-1 - No regression test for multiple or inline links to the same item (P3)

Files changed:
- None by the fixer. The test-writer resolved this by adding tests in `server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert/TaskToNoteEdgeCaseTests.kt` (committed in 956fc38a).

Problem:
The review found that no test covered a task linked to the same item both manually and through a SYNC backlink, or a task whose notes contain inline links to an item it is also manually linked to.

Fix:
No production change needed. The tests `an item linked to the task in both directions is linked to the note once, by its manual link`, `an inline link and a manual link to the same item both survive without a conflict` and `an item with an inline backlink, a manual link and an inline link from the task notes converts without a conflict` cover both cases the review asked for. They run in the full server suite.

Validation:
- `sh scripts/check.sh` (server step `./gradlew --quiet check`, which includes the `ch.sonensei.cockpit.gtd.convert.*` tests) - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npm run api:gen` - Pass, 3 expected generated files changed
- `cd web && npm run check` - Pass (1435 tests, lint clean, build ok; the existing bundle budget warning of 650.48 kB vs 500 kB is a warning only and unrelated to this change)

Full test suite:
- `sh scripts/check.sh` - Pass ("== all checks passed": completion gate tests OK, server ktlint + tests, web eslint + tests + build, android ktlint + unit tests + lint)

Lint/type-check/build:
- Included in `cd web && npm run check` and `sh scripts/check.sh` - Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh`, run 2026-10-21 after the regeneration)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
