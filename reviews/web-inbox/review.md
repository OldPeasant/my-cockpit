# Code Review

Date: 2026-10-20

Task: web-inbox - Inbox processing mode

Scope: task commits 12e0ad0, baf445c, 6a9b4a7 on top of base 13f8ab6 (`git diff 13f8ab6e..HEAD -- web`), committed changes only

Requirements:
- tasks/web-inbox.md acceptance criteria and recorded assumptions
- SPEC.md §4.2 (ETag/If-Match), §5.3 (inbox processing mode), §5.4 (API)
- docs/ARCHITECTURE.md (web conventions, i18n, lint, coverage)
- AGENTS.md project notes
- Existing code: `gtd/data` stores (`ListStore`, `TaskStore`, `ProjectStore`), `gtd/ui` pickers, `shell/hotkeys.ts`, server `TaskController`/`TaskDtos` for tickler semantics

Git range:
- Branch: main
- HEAD: 6a9b4a727d18ba6aa17e9f136ecf279d72b36f46
- Base/start: 13f8ab6e4401847f53e99c3631be0e87809a3578
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 2 P2, 3 P3

Notes on verification: the production code, unit spec and e2e spec were read in full. Lint, Vitest and Playwright were not run by this review (the reviewer's sandbox denied running them; the parent runs the e2e). No acceptance-criterion evidence was recorded yet (`tasks.py verify web-inbox` shows all four unticked), which is expected at this point and is not reported as a finding. The parent must record evidence for every criterion, including the real Playwright run for #4.

What was checked and found correct:
- All required actions exist with shortcuts; To reference and To calendar are disabled, and their keys do nothing (`actionForKey` skips disabled entries).
- "x items left" uses an ICU plural, and all user-facing strings use `i18n`/`$localize` with ids.
- Each action calls the intended endpoint. The inline-attribute PATCH runs first, and the following move/complete uses the version that the PATCH returned (If-Match chain is correct).
- The `matchesTask` tickler fix matches the server. `TaskController` documents that lists exclude tasks whose `ticklerDate` is after today, for every list.
- Keyboard handling defers to overlays, typing targets, modifiers, `defaultPrevented` and repeats, so it does not conflict with the shell's `Q`/`?`/`Ctrl+K`.
- Rollback on error works through the store's optimistic overlay, and the `busy` set always clears in `finally`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Inline-attribute draft can silently overwrite a concurrent change, bypassing If-Match**
    - File: `web/src/app/features/gtd/inbox/inbox-page.ts:75` (draft), `web/src/app/features/gtd/inbox/inbox-page.ts:194-208` (patch built against the current version), `web/src/app/features/gtd/inbox/inbox-actions.ts:143`
    - Category: Correctness
    - Problem: `clarification` is a `linkedSignal` keyed only on `current()?.id`. It holds a full snapshot of energy, creativity, estimate and contexts, taken when the item first comes up. If the item changes on the server while it is shown (another device or tab; the store refetches on SSE events), the id does not change, so the draft keeps the old values. `run()` then calls `clarificationPatch(task, clarification)` with the *new* `task`. Every field where the stale draft differs from the new server value goes into the PATCH, with `null` for a field the draft did not have, and it uses the new version as `If-Match`. The server accepts this, and the remote change is reverted even though the user never touched that field.
    - Why it matters: this is the lost update that SPEC.md §4.2 optimistic locking exists to prevent. Here the 412 never fires because the If-Match comes from the refreshed copy, not from the copy the user edited.
    - Suggested fix: keep only the fields the user changed (e.g. `Partial<Clarification>` of touched fields, merged over `clarificationOf(current())` for display), and send only those. Alternatively, build the patch and `If-Match` from the task snapshot the draft was based on, so a concurrent change produces a 412. Add a unit test: change the item via a refetch while processing, then run an action and assert that untouched fields are not sent.

- [ ] **CR-2 | The `matchesTask` tickler change has no unit test**
    - File: `web/src/app/features/gtd/data/task-store.ts:152-153`
    - Category: Tests
    - Problem: the task changed shared filtering logic, used by every `TaskStore` view: tasks with a future `ticklerDate` no longer match any list. `task-store.spec.ts` has no case for `ticklerDate`, so it does not cover the future, today and past boundaries. The inbox spec covers it only indirectly, through the mocked move response in "T puts the item in a tickler".
    - Why it matters: this is regression-prone shared behavior outside the task's `touches`. A later change to `matchesTask` (or a `>=` slip) would make ticklered items reappear in lists, or due ticklers vanish, without any failing test.
    - Suggested fix: add `matchesTask` cases in `task-store.spec.ts`: `ticklerDate` tomorrow does not match, `ticklerDate === today` matches, a past date matches, and none matches.

## P3 Low

- [ ] **CR-3 | "Enter" does not confirm the To-project prompt when an existing project is selected**
    - File: `web/src/app/features/gtd/inbox/inbox-page.html:132-150` (`<select #promptField>`) and `:183-186` (Move button with `<kbd>Enter</kbd>`)
    - Category: Correctness
    - Problem: for the project prompt, the autofocused field is a `<select>`. HTML implicit form submission applies to text-like `<input>` fields, not to `<select>`, so pressing Enter there does not submit the form, although the Move button advertises `Enter`. The keyboard user has to Tab to the button. The unit test calls `form.requestSubmit()` directly and so does not catch this.
    - Why it matters: the processing mode is meant to be keyboard driven (SPEC.md §5.3), and the visible hint is wrong for the most common To-project path.
    - Suggested fix: handle `(keydown.enter)` on the select by calling `confirmPrompt`, or adjust the hint. Cover it with a keydown-based test rather than `requestSubmit()`.

- [ ] **CR-4 | Inbox load error has no retry**
    - File: `web/src/app/features/gtd/inbox/inbox-page.html:27-28`
    - Category: Correctness
    - Problem: when the initial `listTasks` fails, the page shows "The inbox could not be loaded." with no way to retry except a full reload (or a later SSE resync). Other feature pages (`trash-page.html:18`, `settings/general/general-settings.html:9`) offer a retry button that calls `load()`.
    - Why it matters: an unrecoverable error state, inconsistent with the existing project pattern.
    - Suggested fix: add a retry button that calls `tasks.refresh()` (i18n id e.g. `@@inbox.retry`).

- [ ] **CR-5 | A failed move after "new project" leaves an orphan project, and a retry creates a duplicate**
    - File: `web/src/app/features/gtd/inbox/inbox-page.ts:249-255`
    - Category: Correctness
    - Problem: in the To-project / New project path, `projects.create` succeeds before `tasks.move`. If the move fails (e.g. a 412 because the item changed), the item returns to the inbox, but the new project stays. Retrying the same action with the same title creates a second project with that title.
    - Why it matters: only on the error path, but it leaves user-visible duplicates that the user has to clean up by hand.
    - Suggested fix: after a successful create, preselect the created project for the retry (e.g. set `promptProject` to its id when the move fails), or record this as an accepted limitation with `tasks.py assume`.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (lost update that bypasses If-Match) and CR-2 (test for the shared `matchesTask` change) should be fixed or explicitly dispositioned. The P3 items are optional improvements. No P0/P1 issues: the acceptance criteria appear to be implemented as specified, pending the evidence that the parent records for criteria #3 (`npm run check`) and #4 (the real Playwright run).
