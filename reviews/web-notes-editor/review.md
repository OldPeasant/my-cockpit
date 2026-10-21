# Code Review

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: `git diff 3a238e64..1481ea4d` (15 task commits, `git log --oneline 3a238e64..1481ea4d`). Committed changes only. This round looks closely at 0fc40673, the fix for the previous P3 CR-1 (`flushAll()` never ended when the server normalises the title). It covers `flushAll()`, `persist()` (title adoption, destroyed path), the `sameDraft()` helper and the 3 new tests in `note-editor-page.spec.ts`. Earlier commits were reviewed in previous rounds. `note-editor-page.ts` was read in full again to check how it works with autosave, conflicts, uploads and leave/switch. While this review ran, the branch was rebased onto the new main. HEAD is now 6658d14a (base 142a83ba), and 3a68e15e is the rebased 0fc40673. `git diff 1481ea4d 6658d14a -- web/src/app/features/notes` is empty, so the reviewed code is the same at both hashes.

Requirements:
- `tasks/web-notes-editor.md`: acceptance criteria 1-5 and the recorded assumptions (autosave and flush on leave at 06:31, discard on leave in conflict at 07:01, stay on a note whose save fails at 07:26 and 07:36)
- `SPEC.md` §8.1, §4.2 (If-Match, 409/412)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md`, `AGENTS.md`
- `reviews/web-notes-editor/fixes.md` (fifth cycle)
- Server contract for comparison: `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt:277-278` (the title is trimmed, `bodyMd` is stored unchanged)

Git range:
- Branch: main
- HEAD: 1481ea4d (reviewed; same editor code as the rebased 6658d14a)
- Base/start: 3a238e646541fffa63974cba21b88a41dc1f6016
- Commits reviewed: 15

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification of 0fc40673 (previous CR-1)

- **The loop ends.** Each round of `flushAll()` (`note-editor-page.ts:165-176`) stores the draft it starts with. The loop goes on only when, after the round, the note is still dirty, the draft has changed (something was typed), and the state is neither `conflict` nor `error`. A server that normalises a field can no longer keep the loop running: without new typing, the next check ends it. The server changes only the title (`NoteService.kt:277`); `bodyMd` is stored as sent (`:278`), so there is no body mismatch that title adoption would miss.
- **Title adoption** (`:331-334`): the server's title is adopted only when it differs from the title sent and the title was not retyped during the save. After that, `draft().title` equals `saved.title` (`'' -> null` covers a title the server trims to empty), so `changes()` is null, the state becomes `Saved`, `beforeunload` does not warn and no autosave repeats. Only the title signal is set and `schedule()` is not called, so no extra save starts. Body, upload placeholders and editor state are not touched.
- **Destroyed path** (`:338-341`): `flush()` runs again only when the draft changed since it was sent. Otherwise the state becomes `pending` and nothing more is sent. Saves stay serialised through `inFlight` as before.
- **Interaction checks:**
    - Autosave in flight when leaving starts: `flushAll()` stores the draft and then waits in `flush()` for the running save. If that save adopts the title, the note is clean and the loop ends after a round with nothing to send.
    - Typing during the save on leaving (in-app switch): `onTitle`/`onBody` change the draft, so the loop runs another round with the new draft and the new version's If-Match (the earlier fix still works).
    - Conflicts: the 409/412 path is unchanged, and `flushAll()` still stops on `conflict`. `keepMine()` goes through `flush()` → `persist()`, so title adoption works there too.
    - Uploads: `leave()` still runs `flushAll()`, then `finishUploads()`, then `flushAll()`. Placeholders are removed by `draft()`, and adoption touches only the title.
    - Switch after a failed save: `open()` (`:138-141`) still stays on the note when the state is `error` and the note is dirty. A loop that ends with `pending` (a normalised field without adoption) moves on to the other note, and `show()` then clears the timer.
- **Tests:** the 3 new tests cover the in-app switch, autosave title adoption and the destroyed path, with a PATCH response whose title differs from the one sent. Each checks that no PATCH follows over several debounce periods. They check behaviour (requests and the shown title), not internals. I could not run tests in this review because the sandbox denied running `ng test`. The results are taken from `fixes.md` (fifth cycle: 110 editor tests pass, `npm run check` and `.taskcheck` pass) and from the test-writer's report.

## Acceptance criteria evidence

`python tasks.py verify web-notes-editor` lists criteria 1-5 as not yet recorded. Recording them is the parent's job before `done`. `reviews/web-notes-editor/tests.md` has evidence for each criterion, including AC5 run against the docker compose e2e stack in chromium. The parent must record that evidence (or a fresh run on the final code) with `tasks.py verify`.

## Review conclusion

No material issues found.
