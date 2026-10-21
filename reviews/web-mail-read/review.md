# Code Review

Date: 2026-10-21

Task: web-mail-read - Mail reading view

Scope: final review round on revision 33cbef07. The whole task diff is `git diff 32667af6..33cbef07`. Round 2 reviewed it up to 08edb6f2 (0 findings). The task commits were later rebased onto main, so 08edb6f2 is now 9d397dea. `git diff 08edb6f2 9d397dea -- web server` is empty, so the code is the same. The only code change since round 2 is commit 33cbef07: 5 new tests in `web/src/app/features/mail/read/mail-read-page.spec.ts` (plus the test-writer's report files). I reviewed that commit in full. I re-checked the rest of the task diff against the round-1 and round-2 results. Committed changes only. CR-4 is still deferred to task `mail-attachment-to-files`, as the parent agreed.

Requirements:
- tasks/web-mail-read.md (acceptance criteria 1-4 and the recorded assumptions)
- SPEC.md §4.1, §4.3, §7.3, §7.4, §8.2 (as cited in round 1)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md artifact contract
- Earlier rounds of this report, reviews/web-mail-read/fixes.md, reviews/web-mail-read/tests.md

Git range:
- Branch: main
- HEAD: 33cbef0758d523c569d91e2d6b053f85e5a97f8e
- Base/start: 32667af6 (as supplied; `git merge-base HEAD origin/main` is now ef7342ad because main moved on)
- Commits reviewed: the task commits ed6679c5, 3fcf340f, 6cec6d15, 50fad8d8, 9d397dea and 33cbef07. 71f4bd85 and fdd52554 only touch tasks/mail-attachment-to-files.md. The other commits in the range are task-state commits (heartbeat/verify) by other agents and contain no code.
- Uncommitted changes: none (working tree clean)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Method

- Read `git show 33cbef07 -- web`, and checked each new test against `mail-read-page.ts` at HEAD (`open`, `showImages`, `showPreview`, `closePreview`, `saveToFiles`, `isSaving`, the `DestroyRef` hook) and against the spec helpers (`deferred`, `create`, `navigate`, `withNotes`, the fake service's `blob`/`saveResult`).
- Ran `cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts'`: 13 files, 276 tests passed. `mail-read-page.spec.ts` has 41 `it` blocks, 36 before this commit, so the 5 new tests are all included and all pass.
- Did not run `npm run check`, `.taskcheck` or the Playwright e2e stack. Those belong to the parent's evidence.

## Review of commit 33cbef07 (no findings)

Each new test targets a real guard in the production code, and each would fail if that guard were removed:

- **"ignores a proxied load for an allowed sender that answers after navigating"** tests the success branch of the second `get`. It hits the `seq !== this.seq` return after `service.get(message.id, true)` (mail-read-page.ts:261). Without that return, the late proxied copy would overwrite "Lunch plans". The test checks both the heading and that `srcdoc` does not contain `image-proxy`. It complements the existing test for the failure branch.
- **"still blocks a second save when coming back to a message whose save runs"** shows that the `messageId/partId` saving key stays set across ID -> OTHER -> ID navigation, because `open()` does not clear `saving`. The button stays disabled, only one save reaches the service, and the button is enabled again once the save ends. The second `click()` lands on a disabled button, so the browser drops it before the component's `saving` guard runs. The test checks what the user sees (disabled button, one save), not that guard. That is acceptable, and the guard itself is covered by the existing "saves a part once while saving" test.
- **"ignores a failed proxied load for an allowed sender after the page is gone"** tests the `this.destroyed` part of the catch guard (line 264). Destroying the fixture does not bump `seq`, so only `destroyed` stops the notification. That makes this a meaningful test for that branch. It first checks `gets` loadImages `[false, true]`, so it knows the proxied request is the pending one.
- **"does not reopen a text preview that was closed while it loaded"** covers `closePreview` versus a late `attachment` answer, through the `this.preview() === pending` identity check.
- **"takes only the latest answer when the same text part is previewed again"** covers the same part on the same message requested twice. A late failure of the first request does not show an error while the second request is still loading, and the second answer is shown. Comparing by part id would get this wrong, so the test guards the reason the identity check exists.

Test quality: the tests use the existing helpers and fictitious data (`example.test`), check DOM and service calls instead of private state, add no new mocks, and weaken or remove no existing tests. They fit the structure of the spec file ("navigating to another message", "attachments" blocks).

## Rest of the task diff

Unchanged since round 2. The earlier conclusions still hold for 33cbef07:

- Round-1 findings CR-1, CR-2, CR-3, CR-5 and CR-6 and tests.md observation 2 are fixed and tested. CR-4 is deferred to `mail-attachment-to-files`, and the task file records it as an assumption.
- Async ordering (`seq` shared by `open`/`showImages`, preview identity, per-message saving keys), the iframe security (static sandbox without `allow-scripts`, CSP meta first, `srcdoc` set as a property), allowlist storage robustness, route integration and the minimal out-of-touches edits to `mail.routes.ts` and `list/` were all checked in round 2. None of these files changed since.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. Commit 33cbef07 adds 5 correct, meaningful regression tests and changes no production code. The whole task diff 32667af6..33cbef07 has no open findings except CR-4, which is deferred by agreement. Before `done`, the parent worker must still record evidence for each criterion with `tasks.py verify`: `npm run check` for criterion 3, and the actual Playwright run `npm run e2e -- e2e/mail-read/read.spec.ts` against the e2e stack for criterion 4. Claiming a criterion without that evidence would be a P1 Tests issue.
