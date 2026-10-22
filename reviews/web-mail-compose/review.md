# Code Review

Date: 2026-10-22

Task: web-mail-compose - Mail compose, reply, forward

Scope: third (final) round. Final code of the task range `3f042d8b6159be030e20ee9566780e2affe3e108..e6e15c374bac7b30ce159da63f89abc13b5f7449`, limited to `web/src` and `web/e2e/mail-compose/compose.spec.ts`. The previous round reviewed the code at dd03a60d7. This round reviews the only code change since then: the test-only diff `git diff dd03a60d7 e6e15c374 -- web`, which adds four tests to `web/src/app/features/mail/compose/mail-compose-page.spec.ts`. Only committed code was reviewed. The working tree was clean.

Reviewed revision: e6e15c374bac7b30ce159da63f89abc13b5f7449

Requirements:
- tasks/web-mail-compose.md (acceptance criteria and recorded assumptions)
- SPEC.md §7.3/§7.4, §4.2 (ETag/If-Match)
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Earlier rounds: reviews/web-mail-compose/review.md at 3be16cce4 (round 1) and 5c364a9ee (round 2, code at dd03a60d7); fixer report reviews/web-mail-compose/fixes.md

Git range:
- Branch: main
- HEAD: e6e15c374bac7b30ce159da63f89abc13b5f7449
- Base/start: 3f042d8b6159be030e20ee9566780e2affe3e108 (task base); dd03a60d7 for this round's delta
- Commits reviewed: 2 since the previous round (5c364a9ee, which holds only the round-2 review report, and e6e15c374). e6e15c374 changes `web/src/app/features/mail/compose/mail-compose-page.spec.ts` (+101 lines), `reviews/web-mail-compose/tests.md`, and the task files `tasks/web-mail-compose.md` and `tasks/and-mail-compose.md`.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Production code unchanged since dd03a60d7

`git diff --stat dd03a60d7 HEAD -- web api server android scripts` lists only `web/src/app/features/mail/compose/mail-compose-page.spec.ts`. No production code, generated API, build file or e2e spec changed. The findings and analysis of the round-2 review therefore still apply to the production code.

## Review of the new tests (e6e15c374)

The new `describe('sender change queued behind other writes')` block (spec lines 927-1026) has four tests. I traced each one against `followSender` (mail-compose-page.ts:388-410), `changeSender` (:188-195), `send` (:230-255) and `ownSent` (:467-469), using the fake service in the spec:

- **"puts From back when the draft got an attachment before the queued move ran".** The upload is held at a gate while `changeSender(OTHER_ACCOUNT)` is called. Once the upload finishes, the queued `followSender` reaches the `!canChangeSender()` branch and calls `back()`. The test asserts that there are no creates, discards or errors, that `accountId` is ACCOUNT again, and that the rendered `mat-select` shows the old address. If the `canChangeSender` guard were removed, the draft would be recreated and the test would fail. The test is correct. See CR-7 for its comment.
- **"keeps a sender picked while an earlier move was failing and moves the draft there".** The first move's `create` waits on a gate and then rejects. The second `changeSender(THIRD)` runs while the first move is in flight. The test checks that `back()` keeps THIRD (its `accountId() === target` guard), that the error is reported once, and that the second queued move recreates the draft in THIRD and discards the old copy (`DRAFT`, `"1"`). If `back()` reset the sender without that guard, the test would fail. The test is correct.
- **"after a failed move, Send treats the mail as sent from the account holding the draft".** With an IMAP draft account and a Google target, the move fails, so Send uses `(DRAFT, "1")` and reports the missing Sent copy. Without `back()`, `ownSent()` would read the Google account and show "Message sent". The test checks this path, which the round-2 review traced only by hand.
- **"sends a moved draft from its new account".** A successful move to a Google account. Send uses the new etag `"2"`, and no Sent-copy warning appears. The create body carries the content (`to`) and the new `accountId`. The test is correct.

Conventions: the tests use the spec's existing helpers (`open`, `call`, `settle`, `deferred`, `fileInput`, `account`, `draft`) and fictitious `.test` addresses. `toHaveBeenCalledExactlyOnceWith` is used across the web suite. The tests assert observable behaviour: service calls, notifications, the selected sender and the rendered select. `npx eslint` and `npx prettier --check` on the spec pass.

Fake limitation: the fake `create` returns the same draft id (`DRAFT`), so the tests that involve a move tell the old and new copies apart only by etag and account. That is enough for what these tests claim. Recording it here so nobody treats it as id coverage.

## Status of earlier findings

- **CR-1 (P1, sender change outside the write queue): Resolved** in dd03a60d7 (see the round-2 analysis). The new tests add coverage for a move skipped because the draft has an attachment, a sender picked while a move is failing, and the Sent-copy account after a failed and after a successful move.
- **CR-2 (P2, Enter in Subject sends): Resolved** in dd03a60d7. No change in this round.
- **CR-3 (P2, URL not updated for new or moved drafts): Resolved** in dd03a60d7. No change in this round. The timing gap described in CR-6 remains.
- **CR-4 (P2, missing tests): Resolved.** The coverage is stronger with e6e15c374.
- **CR-5 (P3, Files picker hides entries beyond 200): Resolved (as a note)** in dd03a60d7. There is still no paging, which is acceptable as stated in round 2.
- **CR-6 (P3, a save finishing while leaving can cancel the user's navigation): Open.** Production code is unchanged, so the finding still applies. Details below.

Validation run in this review:
- `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` at e6e15c374 (clean working tree) -> 18 files, 396 passed. That is the 392 reported for dd03a60d7 plus the 4 new tests.
- `cd web && npx eslint src/app/features/mail/compose/mail-compose-page.spec.ts` -> no problems. `npx prettier --check` on the same file -> passes.
- Playwright and Gradle were not run, as instructed. Criterion #3 still needs the Playwright run against the e2e stack as evidence before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | A save that finishes while the user is leaving the page can cancel the user's navigation** (open, carried over from round 2)
    - File: `web/src/app/features/mail/compose/mail-compose-page.ts:139`, `:351-359`
    - Category: Correctness
    - Problem: `left` is set only in the `DestroyRef` callback, which runs once the new route has been activated. Suppose the user starts a navigation away (the "Mail" back link, browser Back, the app menu) while a first create or a sender move is in flight. If that write finishes before the new route activates, `apply()` calls `showInUrl()`, which calls `router.navigate([], { relativeTo, queryParams: { draft }, replaceUrl: true })`. The Angular router cancels the earlier navigation in favour of this newer one, so the user stays on the compose page. With browser Back, the replaced URL can also overwrite the history entry. The window includes loading the lazy `mail-list-page` chunk (mail.routes.ts:19).
    - Why it matters: Once in a while a Back click does nothing, or browser Back ends up on the compose URL again. Nothing is lost, because the draft is saved, but the navigation looks broken.
    - Suggested fix: Skip `showInUrl` while another navigation is in progress (`router.currentNavigation()` / `getCurrentNavigation()` is non-null and is not one this page started), or set `left` on `NavigationStart` for a URL outside this route. Add a test in which a navigation is in progress when the create resolves.

- [ ] **CR-7 | A new test's comment describes a user action the UI does not allow** (new, test code only)
    - File: `web/src/app/features/mail/compose/mail-compose-page.spec.ts:943`
    - Category: Tests
    - Problem: The first new test calls `changeSender` while an upload holds `busy = 'attach'`, and its comment reads "Picked while the upload is on its way". In the template, the From select is `[disabled]="!canChangeSender() || !!busy()"` (mail-compose-page.html:74), so a user cannot pick a sender during an upload. Other orders are also safe: a sender picked before an attachment is added queues its move ahead of the attachment write. So the `!canChangeSender()` branch in `followSender` (mail-compose-page.ts:394-397) is a defensive guard that no UI path reaches today. The test is valid as a test of that guard, but the comment presents it as a real user flow.
    - Why it matters: A later maintainer may think the select is meant to stay usable during uploads, or may remove the disabled binding on the assumption that this test covers it.
    - Suggested fix: Reword the comment, for example "The select is disabled while busy; this drives the guard in followSender directly". No production change is needed.

## Review conclusion

No material issues found. CR-1 to CR-5 remain resolved at e6e15c374bac7b30ce159da63f89abc13b5f7449, whose production code is identical to dd03a60d7. The four new tests are correct, test what their titles claim, and pass (396 passed). The open findings are CR-6 (P3, carried over) and CR-7 (P3, wording of a test comment only). The parent worker still has to record evidence for acceptance criteria #1 to #3, including the Playwright run against the e2e stack for #3.
