# Code Review

Date: 2026-10-21

Task: web-notes-editor - Notes: Markdown editor with images

Scope: `git diff 0dfb50ad..7298fcb2` (12 task commits, `git log --oneline 0dfb50ad..HEAD`; de6e1157, 7842cced, 25cdb749 and 9f1dadc4 only touch `reviews/`). Committed changes only; the working tree was clean when this review ran. This review looks closely at 7298fcb2, which fixes the previous P3 CR-1 (edits typed while the save on leaving runs) by adding `flushAll()` to `leave()` in `web/src/app/features/notes/editor/note-editor-page.ts`. The production code of `note-editor-page.ts` was read in full again. Earlier commits were reviewed in previous rounds; their production code has not changed apart from 7298fcb2.

Requirements:
- `tasks/web-notes-editor.md`: acceptance criteria 1-5 and the recorded assumptions. These include flushing on leave (06:31), discarding edits when leaving in conflict (07:01), staying on a note whose save fails during an in-app switch (07:26), and history keeping two entries for that note (07:36).
- `SPEC.md` §8.1 (Notes editor), §4.2 (If-Match, 409/412), §4.3 (inline links)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md`, `AGENTS.md`
- `reviews/web-notes-editor/fixes.md` (the 7298fcb2 section)
- Server contract for comparison: `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteService.kt` (`apply`, `validate`)

Git range:
- Branch: main
- HEAD: 7298fcb29b3651c33327a8d06d56a57a1cba94fe
- Base/start: 0dfb50ade0c3aad53e945cde3a853dc8ec8c6e4b
- Commits reviewed: 12

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `flushAll()` never ends when the saved note cannot match the draft**
    - File: `web/src/app/features/notes/editor/note-editor-page.ts:163-166` (with `:89`, `:321-323`; server `NoteService.kt:277`)
    - Category: Correctness
    - Problem: `flushAll()` keeps calling `flush()` while `dirty()` is true and the state is neither `conflict` nor `error`. Normally this ends because each round sends the current draft. The assumption is that a successful save gives back a note equal to that draft. That does not hold when the server normalises a field differently from the client:
        - The client trims the title with JS `String.prototype.trim()` (`:89`).
        - The server trims it with Kotlin `trim()` (`it.value?.trim()?.ifEmpty { null }`, `NoteService.kt:277`). On the JVM this also removes U+001C-U+001F, which JS `trim()` keeps.
        - `validate()` rejects only NUL, so a title such as `"\u001Fabc"` (pasted) is accepted and stored as `"abc"`.

      After that, `changes(saved, draft)` is never null. Every round sends the same PATCH, each with a new version, and the loop never ends. As a result:
        - `open(B)` never continues: the address shows B while the editor stays on A.
        - On page destroy, the PATCHes run back to back with no debounce for as long as the page instance stays in memory.

      Before 7298fcb2 the same mismatch only caused one autosave per second (`persist()` → `schedule()`), and `leave()` still finished after one round.
    - Why it matters: This is a rare input, but the result is an endless stream of requests and a note switch that never completes. It also creates new revisions until the newest-revision rule absorbs them.
    - Suggested fix: Make each round depend on progress. For example, end the loop when the draft that was sent equals the current draft (nothing was typed during the round), so a server that normalises a field cannot keep it running. Optionally, also accept the server's title as the saved title. Add a test where the PATCH response title differs from the sent title and check that `leave()` sends at most one more PATCH.

## Verification of 7298fcb2 (previous CR-1)

- **Resolved.** `leave()` now calls `flushAll()` before and after `finishUploads()`. Edits typed while the leaving PATCH is pending are sent in a second PATCH with the new version's If-Match before the other note is loaded. The committed test `saves edits typed during the save on leaving before opening another note` checks this: the second PATCH has body `one two` and If-Match `"2"`, there is no GET for B before it, and B is then shown. The page-left variant (`... when the page is left meanwhile`) checks the destroyed path: one extra PATCH, then no further PATCH and no GET for B.
- **Termination.** Apart from CR-1, the loop ends:
    - With nothing to send, `flush()` returns and `dirty()` is false.
    - A failed save sets `error`, and a 409/412 sets `conflict`; both stop the loop.
    - A `flush()` started by `persist()` on the destroyed path (`:322`) is serialised through `inFlight`, so no duplicate PATCH is sent. The loop's next `flush()` waits for it and then finds nothing to send.
    - The loop runs on only while the user keeps typing, and each round is a server round trip.
    - The open-time `conflict` and `error` states are still handled by `open()` as before: edits in conflict are dropped (07:01 assumption), and on `error` with a dirty draft the editor stays (07:26 assumption).
- **No autosave timer reaches the next note.** Each `flush()` clears the timer first, so after a successful `flushAll()` a timer can remain only if a keystroke during the last round was undone (the draft equals the saved note again). If that timer fires while B loads, `flush()` returns because `note()` is null. `show(B)` clears it when B arrives. The committed test advances `2 × AUTOSAVE_DEBOUNCE_MS` after B is shown and expects no PATCH. In the stay-on-error path a leftover timer only retries A's own save, which is the intended autosave.
- Previous CR-2 (duplicate history entry after a failed switch) is accepted as the 07:36 assumption and is not raised again.

## Other checks performed

- Focused specs: `cd web && npx ng test --watch=false --include='src/app/features/notes/editor/**/*.spec.ts'` → 5 files, 103 tests passed (clean tree at 7298fcb2).
- Lint: `cd web && npx eslint src/app/features/notes/editor` → no findings.
- `fixes.md` (7298fcb2 section) matches the code change. The doc comments of `leave()` and `flushAll()` describe the behaviour correctly, apart from the progress assumption in CR-1.
- The previously verified behaviour is unchanged by this commit: the escaped renderer, If-Match and the 409/412 handling, the `loadSeq`/`openSeq` guards, the localStorage fallback, and the route and dependency assumptions.

## Notes for the parent worker (not findings)

- `npm run check` and the Playwright e2e run (criterion 5) were not re-run in this review. Record them from the refreshed `tests.md` or your own run on HEAD.

## Review conclusion

No material issues found.
