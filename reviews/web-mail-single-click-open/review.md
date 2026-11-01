# Code Review

Date: 2026-11-01

Task: web-mail-single-click-open - Mail list: a single click opens the message

Scope: final revision of the task. Code diff `git diff c3567526d de35fb936 -- web`. c3567526d is the last main commit before the task. The task's own `assume` and `heartbeat` commits (43f563e39, 9004ac608, 5a2c43bf5, b921f086b) change only `tasks/`. Task code commits: 983259fb0 (implementation), 94e93b614 (test-writer tests, first review evidence), 4b6cee108 (fixes for CR-1 and CR-2 from the first review), 3630ebe3b (review evidence only), de35fb936 (e2e folder-tree locators). This re-review covers the whole diff and looks most closely at de35fb936, the only code change since the previous review at 6b447800a.

Requirements:
- tasks/web-mail-single-click-open.md acceptance criteria and the two recorded assumptions
- AGENTS.md project notes (conventions, i18n, touches)
- previous review at 6b447800a (no findings) and reviews/web-mail-single-click-open/fixes.md
- the mail list and folder tree code (web/src/app/features/mail/list), including mail-folder-tree.ts
- the web/e2e suites that drive the mail list and folder tree

Git range:
- Branch: main
- HEAD: de35fb9364638b443b0f43aac0d27dc6d78084ca
- Base/start: c3567526d
- Commits reviewed: 5 code/evidence commits (983259fb0, 94e93b614, 4b6cee108, 3630ebe3b, de35fb936)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Change since the previous review (de35fb936)

- `git diff 6b447800a de35fb936` changes only two code files besides `tasks/` and `reviews/`: `web/e2e/mail-list/list.spec.ts:63` and `web/e2e/phase5/demo.spec.ts:337`. The rest of the web diff is the same as the code reviewed before (the earlier commits were rebased, and the per-file stat against c3567526d matches).
- The new locators match the current app. `web/src/app/features/mail/list/mail-folder-tree.ts:28` renders each account as `section [attr.aria-label]="tree.account.emailAddress"`, so the region's accessible name is the email address and no longer the display name. The phase 5 demo uses `ADDRESS` (`kim-{run}@cockpit.test`, line 18), and list.spec uses `address` (`list-{run}@cockpit.test`, line 18). Both are the `emailAddress` of the account that spec creates. Both contain a per-run id, so Playwright's default substring name match finds exactly one region even when accounts from earlier runs exist.
- `ACCOUNT_NAME` is still used in the phase 5 demo (form fill at line 90, account list check at line 100), so nothing is left unused. No other e2e spec looks up the "Mail folders" tree or its regions.
- The edit is outside `touches`, but it is recorded as an assumption, and it is needed to run the e2e evidence. It only changes test locators to match the done task mail-account-label-email, and no product code changes.
- The parent reports `npm run e2e -- e2e/mail-read e2e/mail-compose e2e/mail-list e2e/phase5/demo.spec.ts` on the full compose stack -> 38 passed, including all 6 phase 5 demo steps. This review only checks that the locators are correct. The parent records that run as the evidence.

## Verified without findings (whole task diff)

- Criterion 1: the row is `<a class="open" [routerLink]="['/mail/messages', item.id]">` in mail-list-page.html. The router builds the `href`, so the browser shows the target on hover. A plain primary click navigates in the app. Ctrl, Cmd, Shift and middle clicks are left to the browser. Unit specs cover the href, a plain click (navigates, moves the cursor, selects nothing) and the modified clicks. The e2e specs read.spec, compose.spec and the phase 5 demo open a message with `a.open` click.
- Criterion 2: the checkbox is a sibling of the link and only toggles the selection. A spec checks that it does not navigate. The shortcut handler is unchanged. The row's `(keydown.enter)` opens the message once, and modified Enter stays native. `o` opens the cursor message, also after a click. Specs check that x and e still act on a clicked row.
- Criterion 3: `(dblclick)` is removed. The hint says "o or click open" in the template and in `messages.xlf`, and "o oder Klick öffnen" in the German target. The unit id is unchanged. The locale edit is recorded as an assumption.
- Criterion 4: the old click-only-moves-cursor spec is replaced, and the specs for click, checkbox, `o` and Enter are present. The parent records `npm run check` as the evidence.
- Accessibility, styling and security: the link keeps the former button's content, accessible name, colour and focus-visible outline. Roving focus still targets `.open`. The change adds no input handling, and the router builds the URL from the item id.

## Review conclusion

No material issues found.
