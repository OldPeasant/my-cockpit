# Code Review

Date: 2026-10-22

Task: web-weekly-review - Weekly review wizard

Scope: committed task changes `8fc91c0a..0b0dfb92` (commits c1407cba wizard, 26555389 session/page tests, 8e508c43 e2e spec, 1fae11dc step component tests, 0b0dfb92 more session and reflection tests): `web/src/app/features/review/**`, `web/e2e/review/review.spec.ts`, `web/playwright.config.ts`, `web/src/app/app.spec.ts`

Requirements:
- tasks/web-weekly-review.md acceptance criteria and recorded assumptions
- SPEC.md §8.6 (Weekly Review wizard), §8.5 (journal), §9.1 (web client)
- docs/ARCHITECTURE.md (web conventions, i18n, testing rules)
- AGENTS.md project notes
- Parent instructions (review committed code, do not run Playwright)

Git range:
- Branch: main
- HEAD: 0b0dfb92d00b7724681fc40a60b81b5eee6c91bd
- Base/start: 8fc91c0abee46c1ee0b269556c9bafcc05d44258
- Commits reviewed: 5

Review method note: this reviewer was denied shell access (git), so `git diff`/`git show` could not be
printed. The changed files were read in full from the working tree with the file reader, along with
their direct dependencies (InboxPage, the generated `updateProject`/`setProjectNextStep` clients,
`followUpTask`, app routes, the playwright config, ARCHITECTURE.md and SPEC.md §8). This update covers
the final revision 0b0dfb92. The parent says that commit changes only tests:
`web/src/app/features/review/review-session.spec.ts` (more tests) and the new
`web/src/app/features/review/review-page-reflection.spec.ts`. Both files were read in full and checked
against the current `review-session.ts`, `review-page.ts`/`.html` and `steps/reflection-step.ts`.
Production code is the same as in the earlier review, so findings CR-1..CR-3 still apply.

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | If "Add next step" partly fails, the typed step is cleared and a duplicate task is likely**
    - File: `web/src/app/features/review/steps/projects-step.ts:121`
    - Category: Correctness
    - Problem: `addNextStep` creates the NEXT task, clears the draft (line 125), and then calls
      `setProjectNextStep`. If that second call fails, `decide` keeps the project in the list as stalled
      without any status message. The typed text is gone, but the task already exists in the project.
      If the user types it again and presses Add, a second identical task is created.
    - Why it matters: a temporary error leaves a half-done action the user can't see, and probably a
      duplicate next action. The similar flow in `InboxPage.apply` ('project') counts the created task
      as success and ignores only the next-step failure.
    - Suggested fix: clear the draft only after both calls succeed. If `setProjectNextStep` fails,
      remember the created task id for that project so a retry only sets the next step. Alternatively,
      treat the task as added, as InboxPage does.

- [ ] **CR-2 | A completion that fails after the reflection was saved empties the textarea without saying where the text went**
    - File: `web/src/app/features/review/review-session.ts:96`
    - Category: Correctness
    - Problem: `complete()` saves the reflection to the journal and then clears it (`setReflection('')`)
      before calling `completeWeeklyReview`. If completion fails with a non-conflict error (e.g. 500 or a
      network error), the review stays on step 8 with an empty reflection field and only the generic HTTP
      error is shown. The protection against appending twice works, and the new test 'does not append
      the reflection twice when completing is retried' pins it. But the user is not told the text is
      already in today's journal. If they retype it and finish, a second "## Weekly review" section is
      appended.
    - Why it matters: the user sees what looks like data loss, and the journal can end up with
      duplicate content.
    - Suggested fix: instead of clearing silently, keep a "reflection saved to journal" flag or status
      line, e.g. show "Saved in today's journal", and skip the journal write on retry while the text is
      unchanged.

- [ ] **CR-3 | `daysSinceLast` is a public writable signal, unlike the rest of the session state**
    - File: `web/src/app/features/review/review-session.ts:40`
    - Category: Quality
    - Problem: `phase`, `review`, `saving` and `reflection` are exposed through `asReadonly()`, but
      `daysSinceLast` is a public `WritableSignal`. Components (the page template, or steps that inject
      the session) could set it.
    - Why it matters: it breaks the single-owner pattern of the other session state and gains nothing.
    - Suggested fix: make it a private `signal` with a public `asReadonly()` view, like the others.

## Observations checked without findings

- All 8 steps of SPEC.md §8.6 are present in order (`review-steps.ts`), and the page renders each one.
  Step 1 embeds the real `InboxPage` processing mode. Steps 6 and 7 offer keep/someday/delete and
  activate/keep/delete. The projects step offers next step, status change (PATCH with a partial body)
  and keep. The waiting step offers still waiting/follow up/done.
- Resume: the step is saved with If-Match on every move, and conflicts (404/409/412) reload the review.
  Navigation is disabled while a move is saving, and `goTo` and `complete` are guarded against running
  twice. The reflection draft is kept per review id in localStorage, and storage failures are caught.
- Journal: the reflection is appended to today's entry before completion. Mood and tags are kept, the
  ETag is sent when the entry exists, and a 404 on the read is silent. If the journal read or write
  fails, the review is not completed.
- Loading and error states: the page and every step show a loading state, and an alert with retry
  when loading fails. When an action fails, the item stays and the busy flag is released.
- i18n: user-facing strings and aria labels are externalized with ids.
- Tests (session and page specs up to 1fae11dc): start, step navigation (next/back/rail), resume at the
  saved step, conflict reload, saving lock, completion with and without a reflection, retry without a
  double append, and draft resume. Step specs exist for the calendar, reflection, get-clear and list
  steps. The e2e spec walks all 8 steps and checks server state. It runs in the `inbox-exclusive`
  Playwright project (workers: 1) because it empties the shared inbox, as recorded in the task
  assumptions.
- Tests added in 0b0dfb92 (checked against the current production code):
  - `review-session.spec.ts` adds:
    - a single move, and a single completion, while one is still running (a deferred mock call);
    - 404 on a move and 409 on completion, which reload the review to the start screen;
    - a failed start, which stays idle with saving released;
    - a failed journal read, which does not overwrite or complete;
    - the silent-error `HttpContext` on the journal lookup;
    - drafts kept per review id, a draft removed when cleared, and no draft stored before a review
      is open;
    - storage that cannot be read or written (via `vi.spyOn(Storage.prototype, ...)` restored in
      `afterEach`).

    Each assertion matches the behavior of `review-session.ts` (`savingState` guards, `saveWith`
    conflict handling, `show`/`setReflection` try/catch, `saveReflection` 404 handling).
  - `review-page-reflection.spec.ts` renders the real `ReviewPage` and `ReflectionStep` with the other
    steps stubbed through `overrideComponent`. This proves the reflection step shares the page-provided
    `ReviewSession`. Typed text is written to the journal (`date`, `bodyMd`, `mood: null`, `tags: []`)
    and the review completes, with the draft removed. A stored draft is shown on resume. The text
    survives Back/Next. The button labels and headings used ('Finish review', 'Back', 'Next step',
    'Reflection', 'Someday/Maybe', 'Review complete') match the current template and step titles.

  The tests assert behavior through public signals, the API calls made and the DOM. The mocks stand in
  only for the HTTP boundary. Test data is fictitious. No tests were weakened or skipped.
- No secrets, credentials or unsafe HTML rendering were introduced.

## Review conclusion

No material issues found.
