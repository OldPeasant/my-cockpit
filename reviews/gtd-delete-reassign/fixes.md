# Fix Report

Date: 2026-10-26

Task: gtd-delete-reassign - Server-side reassignment when deleting a context or area

Scope: current task; finding CR-1 from `reviews/gtd-delete-reassign/review.md`

## Fixed findings

### CR-1 — A replacement target deleted elsewhere gives an unhelpful 400 and leaves the stale list in place

Files changed:
- `web/src/app/features/settings/gtd/gtd-settings.ts`
- `web/src/app/features/settings/gtd/gtd-settings.spec.ts`

Problem:
When the target context/area was deleted elsewhere, the server answers `DELETE /contexts/{id}?replaceWith=` or `DELETE /areas/{id}?moveTo=` with a 400 that has a field error on `replaceWith`/`moveTo` and the generic detail "Request validation failed". Before this fix the section showed that generic text and did not reload the list, so the missing target could be picked again.

Fix:
Added `isMissingTarget(kind, error)`. It is true for an `ApiError` with status 400 whose `errors` contain the field `replaceWith` (contexts) or `moveTo` (areas). `save()` now reloads the list for this case as well as for the existing 404/412 stale case. `message()` shows a localized text in this case: `@@gtdSettings.contexts.missingTarget` "The context to move to no longer exists; the list now shows the current state." or `@@gtdSettings.areas.missingTarget` "The area to move to no longer exists; the list now shows the current state." Any other 400 behaves as before: the server's message is shown and the list is not reloaded. The server is unchanged. `src/locale/messages.xlf` has no `@@gtdSettings.*` entries and is left to a separate extraction (commit 80411290d), so it was not edited.

New Vitest tests in `gtd-settings.spec.ts` ("delete edge cases"):
- context delete with a 400 on `replaceWith`: the list reloads, the specific message appears, and the generic detail does not;
- area delete with a 400 on `moveTo`: the list reloads and the specific message appears;
- context delete with a 400 whose field error is not `replaceWith`: no reload, and the server detail is shown.

The existing test "keeps the context and shows the error when the server rejects the replacement" (400 with no field errors, no reload) still passes unchanged.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/gtd/**/*.spec.ts'` — Pass (3 files, 76 tests)
- `cd web && npm run lint` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/gtd/**/*.spec.ts'` -> 3 files, 76 tests passed

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e typecheck, test:ci, ng build) -> passed: 174 test files, 3628 tests, build complete

Lint/type-check/build:
- `cd web && npm run lint` -> All files pass linting
- `npx prettier --check src/app/features/settings/gtd/` -> clean

`.taskcheck`:
- Not run. This change affects only the web code, and `npm run check` covers the web part. The parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
