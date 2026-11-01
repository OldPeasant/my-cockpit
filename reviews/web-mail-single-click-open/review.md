# Code Review

Date: 2026-11-01

Task: web-mail-single-click-open - Mail list: a single click opens the message

Scope: final revision of the task. Code diff `git diff c3567526d 6b447800a -- web` (c3567526d is the last main commit before the task. `git merge-base HEAD origin/main` resolves to 43f563e39, the task's own `assume` commit, which changes only `tasks/`). Task commits: 460f785c1 (implementation), 39a008a96 (test-writer tests, first review evidence), 6b447800a (fixes for CR-1 and CR-2 from the first review).

Requirements:
- tasks/web-mail-single-click-open.md acceptance criteria and recorded assumption
- AGENTS.md project notes (conventions, i18n, touches)
- previous review (CR-1, CR-2) and reviews/web-mail-single-click-open/fixes.md
- existing mail list code and specs (web/src/app/features/mail/list, mail-shortcuts.ts)
- web/e2e suites that drive the mail list

Git range:
- Branch: main
- HEAD: 6b447800ab4aa650119ec777a15f84d5bda25759
- Base/start: c3567526d (merge-base 43f563e39 adds only the task assumption)
- Commits reviewed: 3 (460f785c1, 39a008a96, 6b447800a)
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

## Fixes from the previous review

- CR-1 (e2e specs double-clicked the removed `button.open`): fixed. `web/e2e/mail-read/read.spec.ts:60`, `web/e2e/mail-compose/compose.spec.ts:62` and `web/e2e/phase5/demo.spec.ts:346` now use `locator('a.open').click()`, and the URL and heading assertions after it are unchanged. A grep for `button.open`, `dblclick` and `double-click` in `web/e2e` finds nothing else. The `button.open` matches left in `web/src` belong to other features (files browser, new page), not the mail list. `web/e2e/mail-list/list.spec.ts` does not click rows, so the new navigation on click does not affect it. The parent reports that the Playwright run of the affected suites is still in progress. That run is the evidence that the e2e flows pass. This review only checks that the locators are right.
- CR-2 (stale "row's button" comment): fixed. `mail-list-page.ts:231` now says "row's link".

## Verified without findings

- Criterion 1: the row is `<a class="open" [routerLink]="['/mail/messages', item.id]">` (mail-list-page.html:154). The rendered `href` gives the hover target. RouterLink handles a plain primary click in the app and leaves Ctrl, Cmd, Shift and non-primary clicks to the browser. In real browsers a middle click fires `auxclick` and follows the `href` natively. The specs check the hrefs, a plain click (default prevented, navigates to `/mail/messages/c`, cursor moves, nothing selected, no action applied), and that Ctrl, Cmd and button-1 clicks are left to the browser.
- Criterion 2: the checkbox is a sibling of the link, so its click only toggles the selection. A spec checks that it calls neither `navigate` nor `navigateByUrl`. The shortcut handler (`onKeydown`, `run`) is unchanged. Enter is not a mapped shortcut, so the row's `(keydown.enter)` is the only path that handles it, and a spec checks it navigates exactly once. `keydown.enter` does not match modified Enter, so Ctrl, Cmd and Shift+Enter stay native (covered by a spec). `o` opens the cursor message, also after a click (covered by a spec). Specs added by the test-writer check that x and e still act on a clicked row, and that Enter on a row focused with Tab moves the cursor there first.
- Criterion 3: `(dblclick)` is removed. The hint reads "o or click open" in the template, in `messages.xlf`, and in the German target "o oder Klick öffnen", with the unit id unchanged. A spec asserts the new hint. The locale edit outside `touches` is recorded as an assumption.
- Criterion 4: the old "moves the cursor without opening" spec is replaced, the roving-focus spec looks for `a.open`, and the click, checkbox, `o` and Enter specs are present. The parent reports that lint, prettier and e2e:typecheck pass. `npm run check` is the parent's evidence to record.
- Accessibility and styling: the link keeps the former button's content and accessible name. `text-decoration: none` and the existing `color: inherit` keep the look unchanged, and the `:focus-visible` outline is kept. Roving focus still targets `.open`.
- Security: no new input handling. The URL is built from the item id by the router, not by string concatenation.

## Review conclusion

No material issues found.
