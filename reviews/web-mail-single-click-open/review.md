# Code Review

Date: 2026-11-01

Task: web-mail-single-click-open - Mail list: a single click opens the message

Scope: implementation commit of the task, `git diff d811be192 9bd7628ed` (rebased onto main as 460f785c1; the task's files are identical in both, only generated API files from other tasks differ)

Requirements:
- tasks/web-mail-single-click-open.md acceptance criteria
- AGENTS.md project notes (conventions, i18n, touches)
- existing mail list code and specs (web/src/app/features/mail/list, mail-shortcuts.ts)
- web/e2e suites that drive the mail list

Git range:
- Branch: main
- HEAD: 460f785c1368ddbd54c3a1179a5e72f31f446d95 (rebased copy of 9bd7628ed)
- Base/start: d811be192b2dedccd79deef2e4abad95710908f1
- Commits reviewed: 1

Summary: 0 P0, 1 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Three Playwright suites still double-click `button.open`, which no longer exists**
    - File: `web/e2e/mail-read/read.spec.ts:60`, `web/e2e/mail-compose/compose.spec.ts:62`, `web/e2e/phase5/demo.spec.ts:346`
    - Category: Integration
    - Problem: the row element changed from `button.open` to `a.open` (mail-list-page.html:154). These e2e specs open a message with `locator('button.open').dblclick()`. The locator now matches nothing, so each of these tests waits until it times out and fails. `.taskcheck` does not run them (Playwright is excluded), so `npm run check` passing does not catch this.
    - Why it matters: the mail read, compose and phase 5 demo e2e flows are broken on main by this change. Later tasks that name those suites as evidence would fail for reasons unrelated to their own work. Also, a double-click on the link now navigates on the first click, and the second click lands on the read view.
    - Suggested fix: in the three specs, open the message with a single click on the row link (for example `row.locator('a.open').click()`, or `getByRole('link')` scoped to the row), and keep the existing URL and heading assertions. These files are outside the task's `touches`. Either make this one-line locator change in each file as part of this task and record it with `tasks.py assume`, or create a follow-up task before `done`. Then run the affected suites (`mail-read`, `mail-compose`, `phase5`) against the stack they target.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Stale doc comment still says the row's "button"**
    - File: `web/src/app/features/mail/list/mail-list-page.ts:231`
    - Category: Quality
    - Problem: the `focusCursor` comment says focus moves "to the row's button", but the focused element is now the row link (`a.open`).
    - Why it matters: it gives a misleading picture of the DOM to the next reader. Nothing breaks at runtime.
    - Suggested fix: change it to "to the row's link".

## Verified without findings

- Criterion 1: the row is `<a class="open" [routerLink]="['/mail/messages', item.id]">`, and `RouterLink` is already in the component imports, so an `href` is rendered (hover shows the target). RouterLink handles a plain left click as in-app navigation. It leaves Ctrl, Cmd, Shift and non-primary button clicks to the browser. In real browsers a middle click fires `auxclick`, not `click`, so it opens a new tab natively through the `href`. The specs check the hrefs, a plain click (default prevented, navigates to `/mail/messages/c`, cursor moves, no selection, no action applied), and that Ctrl, Cmd and button-1 clicks are left to the browser.
- Criterion 2: the checkbox is a sibling of the link, not inside it, so its click only fires `(change)` and toggles the selection. A spec checks that neither `navigate` nor `navigateByUrl` is called. The page-level shortcut handler (`onKeydown`, `mail-shortcuts.ts`) is unchanged. Enter is not a mapped shortcut, so the row's `(keydown.enter)` and the document handler cannot both navigate (a spec checks there is exactly one navigation). Angular's `keydown.enter` does not match modified Enter, so Ctrl, Cmd and Shift+Enter keep the browser's new-tab or new-window behavior (covered by a spec). `o` still opens the cursor message.
- Criterion 3: `(dblclick)` is removed. The English source, the template and the German target ("o oder Klick öffnen") are updated in both xlf files with the unit id unchanged. A spec asserts the new hint text.
- Criterion 4: the old "moves the cursor without opening" spec is replaced, the roving-focus spec now looks for `a.open`, and the Enter, checkbox and link specs are added. The parent reports that `npm run check` passed.
- Accessibility: the link keeps the button's accessible name (sender, subject, snippet, chips, the labelled icons, the visually hidden "Unread", the date) and the `:focus-visible` outline. `text-decoration: none` and `color: inherit` keep the visuals unchanged, including for visited links. Roving focus still targets `.open`.
- Security: no new input handling. The route is built from the item id through the router's URL serializer, not by string concatenation.

## Review conclusion

Material issues found; fix before completing the task.
