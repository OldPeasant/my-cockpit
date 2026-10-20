# Fix Report

Date: 2026-10-20

Task: web-next-today - Next Actions and Today views

Scope: findings CR-1 and CR-3 of `reviews/web-next-today/review.md`, limited to `web/src/app/features/gtd/next` (base eef85b3a, task commits edf7dfd2..e5a59187)

## Fixed findings

### CR-1 — Saving a preset while the settings are not loaded overwrites every stored preset

Files changed:
- `web/src/app/features/gtd/next/filter-presets.ts`
- `web/src/app/features/gtd/next/filter-presets.spec.ts`
- `web/src/app/features/gtd/next/next-view.spec.ts`

Problem:
`FilterPresets.save()` and `remove()` built the new list from `presets()`. While the settings were not loaded (for example after a failed load), that list was empty, so the PATCH replaced all stored presets with only the new one (or with none).

Fix:
`save()` and `remove()` are now async and `await this.ensureLoaded()` before they read `presets()`. If loading fails, the error goes to the caller and no PATCH is sent. The view already shows "The preset could not be saved" when `save` rejects. New tests:
- filter-presets.spec: "refuses to save or delete while the settings cannot be loaded, so no preset is lost" (no `update` call);
- filter-presets.spec: "loads the settings before saving, keeping the presets stored on the server" (the server preset stays in the list);
- next-view.spec: "does not wipe the stored presets when saving after the settings failed to load" (the first load fails, a later save keeps the existing server preset and appends the new one).

Validation:
- `npm --prefix web run check` — Pass

### CR-3 — A failed preset deletion is silently undone

Files changed:
- `web/src/app/features/gtd/next/next-view.ts`
- `web/src/app/features/gtd/next/next-view.html`
- `web/src/app/features/gtd/next/next-view.spec.ts`

Problem:
`removePreset` discarded the error. The optimistic rollback brought the preset back without any message.

Fix:
`removePreset` clears `presetError`, then sets "The preset could not be deleted. Try again." when the removal fails. The existing `presetError` alert used to appear only inside the open save form. It now also shows below the preset chips while that form is closed (same `.error` style, `role="alert"`). Opening the save form clears it, as before. The existing test "keeps the view usable when deleting a preset fails" now checks the alert text and checks that opening the save form clears it. That test now opens `/next?energy=LOW` so the save button is shown. Its original assertion (the preset stays) is unchanged.

Validation:
- `npm --prefix web run check` — Pass

## Not fixed

### CR-2 — "Scheduled for today" section can effectively never show a task

Status:
Out of scope

Reason:
The parent worker deferred it to follow-up task `gtd-scheduled-marker`. The reviewer did not ask for a code change in this task.

### CR-4 — Changes outside the task's declared `touches`

Status:
Out of scope

Reason:
The parent worker handles it with a recorded assumption. No code change is needed.

## Validation summary

Focused tests:
- `npm --prefix web run check` (api:check, eslint `--max-warnings=0`, e2e typecheck, Vitest, `ng build`): Pass. One intermediate run failed because the updated test created a second `RouterTestingHarness`. The test was fixed, and on the final run all 49 files and 844 tests passed and lint was clean.

Full test suite:
- `sh scripts/check.sh` — Pass

Lint/type-check/build:
- Included in `npm --prefix web run check` — Pass. The existing warning about the initial bundle budget is not related to this fix.

`.taskcheck`:
- Pass (`sh scripts/check.sh`)

Remaining unresolved findings:
0 of the findings in scope (CR-2 and CR-4 are out of scope, see above)

## Final status

All validated findings fixed and validation passed.
