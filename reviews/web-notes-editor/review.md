# Code Review

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: Final code at HEAD `79f15454` on `main`, task range `588084ba..79f15454` (17 task commits, `git log --oneline 588084ba..HEAD`). Committed changes only, no uncommitted changes. The previous review (no findings) covered production code identical to HEAD: `git diff 1481ea4d HEAD -- web/src/app ':!*.spec.ts'` is empty, and `git diff 1481ea4d HEAD --stat` shows that the only code change since then is `web/src/app/features/notes/editor/note-editor-page.spec.ts` (+264 lines, commit 79f15454: 12 tests, 2 helpers). This round reviews those tests in full. It reads `note-editor-page.ts` again (`open`, `leave`, `flushAll`, `flush`, `persist`, `schedule`, `show`, `changes`, `sameDraft`) to confirm the earlier conclusion on the production code still holds, and it judges the test-writer's observation in `tests.md`.

Requirements:
- `tasks/web-notes-editor.md`: acceptance criteria 1-5 and the recorded assumptions (06:31 autosave/flush on leave, 07:01 conflict on leave drops edits, 07:26/07:36 stay on a note whose save fails, 08:34 server title adopted unless retyped, leaving ends once a round sends nothing new, body never replaced)
- `SPEC.md` §8.1, §4.2 (If-Match, 409/412)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md`, `AGENTS.md`
- `reviews/web-notes-editor/tests.md` (observation on the body), `reviews/web-notes-editor/fixes.md`
- Server contract: `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt:277-278` (title trimmed with Kotlin `trim()`, `bodyMd` stored as sent)

Git range:
- Branch: main
- HEAD: 79f15454324f28e9d50cad1e075be2a5284befd6
- Base/start: 588084ba57566f93f26f56fc26318761523841dd
- Commits reviewed: 17 (production code unchanged since the previous review at 1481ea4d; new code in this round: 79f15454)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review notes

New tests (79f15454), checked one by one against `note-editor-page.ts`:
- Correctness: each assertion matches what the code does. Examples: in `adopts the title the server kept and saves the body typed during the save with it`, `persist()` adopts `'Packing'` (line 332: server title differs from the sent title, and the field was not retyped), then `schedule()` sends `{ title: 'Packing', bodyMd: '- [ ] Passport' }` with If-Match `"2"`. In `keeps a title retyped during the save ...`, the guard `this.draft().title === draft.title` keeps the retyped title. In `when the page is left, saves the body typed during a save whose title the server trimmed`, the destroyed path (line 340) sends exactly one more PATCH because the draft changed since it was sent. The two body tests hit line 341 (destroyed, draft unchanged -> `pending`, no re-save) and the `sameDraft` exit of `flushAll()` (line 172), where `show()` clears the leftover autosave timer.
- Strictness: the new tests use `expectOne` and `expectNone` for exact PATCH counts. The fixer's older tests (lines 643-701) only allow "at most one more", so the new ones are stricter.
- Determinism: fake timers are turned on after `open()` and reset in `afterEach`. Every wait is an explicit `advanceTimersByTimeAsync`. The page spec ran green 2 times in a row (76 tests), and the whole editor suite ran green once (122 tests).
- Behavioural: the assertions use only observable output (the PATCH body and If-Match header, the requested URLs, the title input value, the editor value, the save-state text, the router URL, the retry button). They don't read private fields.
- Error and conflict paths after title adoption (503 stays on the note with draft and `Not saved`, 409 opens the other note) match the 07:26 and 07:01 assumptions.
- Minor naming point (no finding): `when the page is left, saves the body typed ...` first starts a switch to the other note and then leaves the page. That covers the destroyed-during-`flushAll` path, which the name does not mention.

Production code: unchanged since the previous review, and the conclusion still holds. All five acceptance criteria are implemented as described in the earlier rounds.

Test-writer observation ("body never adopted from server"): confirmed. If the server ever stored `bodyMd` differently while the user stays on the page, `persist()` would find `changes(saved, draft)` non-null and call `schedule()` (line 342). That autosaves the same draft about once per debounce, without end. Today this cannot happen: `NoteService.kt:278` stores `it.value` unchanged, and JSON keeps the string as sent. On leave and switch, the `sameDraft` guards (lines 172, 340-341) already stop such a loop, and the two new body tests show that. Not adopting the body is also the deliberate, recorded choice (08:34 assumption), because replacing the editor contents while the user types would be riskier. So it is harmless and not a finding. If a later task adds server-side body normalisation, it must change this client path too.

Evidence status (for the parent): `python tasks.py verify web-notes-editor` shows criteria 1-5 as not recorded yet. They have to be recorded before `done`. That is the parent's job, not a defect in the code.

Commands run by this reviewer:
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` -> 5 files, 122 tests passed
- `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/note-editor-page.spec.ts'` -> 76 tests passed (repeat run)
- `npx eslint --max-warnings=0` and `npx prettier --check` on `note-editor-page.spec.ts` -> clean

## Review conclusion

No material issues found.
