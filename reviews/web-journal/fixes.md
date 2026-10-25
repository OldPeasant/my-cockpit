# Fix Report

Date: 2026-10-25

Task: web-journal - Journal UI

Scope: approved findings CR-1, CR-3 and CR-4 of `reviews/web-journal/review.md`, in `web/src/app/features/journal/` (production code and Vitest specs). CR-5 and CR-6 are out of scope (P3, follow-ups). CR-2 (commit the unit specs) is the parent worker's job.

## Fixed findings

### CR-1 — An entry created elsewhere ends in a 428 loop that cannot be recovered

Files changed:
- `web/src/app/features/journal/journal-page.ts`
- `web/src/app/features/journal/journal-page.spec.ts`

Problem:
A PUT without `If-Match` (the day had no entry when opened) for an entry created elsewhere in the meantime gets 428. `persist` treated only 409/412 as a conflict, so 428 ended in `error`, and "Try again" sent the same request again with the same 428 result.

Fix:
`persist` now treats 428 as a conflict, the same as 409/412. The conflict banner appears, and "Save my version" (`keepMine`) fetches the current entry and saves with its version as `If-Match`.

Validation:
- New spec "treats an entry created elsewhere (428) as a conflict and saves my version over it": PUT without If-Match -> 428 -> conflict state with no retry button -> keep mine -> GET v3 -> PUT `If-Match: "3"` -> Saved. Pass.

### CR-3 — Moving between `/journal` and `/journal/:date` recreates the page

Files changed:
- `web/src/app/features/journal/journal.routes.ts`
- `web/src/app/features/journal/journal.routes.spec.ts`
- `web/src/app/features/journal/journal-page.spec.ts`

Problem:
`''` and `':date'` were two route configs, so the default `RouteReuseStrategy` destroyed the page between today and another day. The old page saved in the background while the new one already loaded the day.

Fix:
One route with a `UrlMatcher` (`journalDay`): it matches no segment (today) or one segment (`date` param), and nothing deeper. The URLs (`/journal`, `/journal/:date`) and the title (`Journal`) stay the same. The page instance is reused and `paramMap` drives `open()`, which saves the day it leaves (`leave()`) before it loads the next one. `journal-page.spec.ts` now uses the real `journal.routes` under `journal` instead of a copy with two routes. The tests that expected a new heatmap request after a route switch were updated, because no reload happens now (that was the behavior this finding asked to remove). The routes spec now checks the single route and the matcher.

Validation:
- New spec "keeps the page between today and another day, saving before the next day loads": same component instance on /journal -> /journal/2026-10-24 -> /journal. Yesterday's GET waits for today's PUT, and the heatmap is not loaded again. Pass.
- `journal.routes.spec.ts`: one route titled Journal that loads `JournalPage`. The matcher accepts `[]` and `[date]` and rejects deeper paths. Pass.

### CR-4 — An image whose upload ends while Preview is shown is lost

Files changed:
- `web/src/app/features/journal/journal-page.ts`
- `web/src/app/features/journal/journal-page.spec.ts`

Problem:
In Preview the editor is destroyed, so it neither replaced the placeholder nor reported a failure. The page only put images in place in `leave()`, and only for uploads still tracked.

Fix:
The page's `uploadImage` now chains a settle step that runs after the editor's own handler. If the placeholder is still in `body()` (the editor did not handle it), `settleUpload` puts the image Markdown in its place and schedules a save, or removes the placeholder and reports the failure through `onUploadFailed`. If the editor is shown, it has already replaced the placeholder, so nothing happens twice. `leave()` now just waits until every tracked upload is settled, then saves.

Validation:
- New spec "puts an image whose upload ends during Preview in the text and saves it": the preview shows the image, the PUT has `Lake\n<image markdown>\n` with `If-Match: "2"`, and back in Write the editor shows it. Pass.
- New spec "reports an upload that fails during Preview and drops its placeholder": banner "The image could not be uploaded: ...", and no placeholder in the text afterwards. Pass.
- New spec "reports a failed upload once while the editor is shown": `onUploadFailed` called once, not twice. Pass.
- The existing image specs (paste, placeholder never saved, upload still running when leaving the day) still pass.

## Not fixed

### CR-5 — "Today" does not move on at midnight

Status:
Out of scope

Reason:
P3. The parent worker recorded it as a follow-up.

### CR-6 — Edits in conflict are dropped when leaving the day

Status:
Out of scope

Reason:
P3. The parent worker recorded it as a follow-up.

### CR-2 — Unit tests not committed at the reviewed revision

Status:
Out of scope

Reason:
The specs exist in the working tree. Committing them is the parent worker's job.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include 'src/app/features/journal/**/*.spec.ts'`: 5 files, 87 tests passed
- `npx eslint --max-warnings=0 src/app/features/journal`: Pass
- `npx prettier --check src/app/features/journal`: Pass (after `prettier --write` on journal-page.ts)

Full test suite:
- `cd web && npm run check`: Pass (171 test files passed). The initial-bundle budget warning was already there and is unrelated.

Lint/type-check/build:
- `cd web && npm run e2e:typecheck`: Pass (web/e2e/journal/journal.spec.ts typechecks; e2e not run, as instructed)

`.taskcheck`:
- `sh scripts/check.sh`: Pass ("all checks passed")

Remaining unresolved findings:
0 of the supplied in-scope findings (CR-5, CR-6 are out of scope)

## Final status

All validated findings fixed and validation passed.
