# Code Review

Date: 2026-11-10

Task: and-mail-read-fullheight - Android read view: mail at full height in one page scroll, linked items below

Scope: round 2. Committed changes under `android/` from the task base `d773e6bd1` to HEAD (`git diff d773e6bd1..HEAD -- android`), production and tests: `MailBodyView.kt`, `MailReadScreen.kt`, `MailBodyViewTest.kt`, `MailWebViewTest.kt`, `MailReadScreenTest.kt`, `MailReadNoteLinksTest.kt`, `MailReadTestSupport.kt` (all in `android/app/src/{main,test}/java/ch/sonensei/cockpit/mail/read/`). Static review only: Gradle was not run, as the parent asked (a test-writer runs Gradle in this clone at the same time).

Requirements:
- `tasks/and-mail-read-fullheight.md` acceptance criteria 1-9, the recorded assumptions (wrap-content measuring and zoom trade-off; notice and progress bar fixed; attachment list keeps its 168dp box) and the recorded skips of criteria 2, 3 and 8
- `SPEC.md` (read view, linked items panel)
- `AGENTS.md` project notes (conventions, lint, evidence rules)
- Round-1 review, `reviews/and-mail-read-fullheight/tests.md` and `reviews/and-mail-read-fullheight/fixes.md`
- Parent instructions for round 2

Git range:
- Branch: main
- HEAD: d868e21e210af0a3f98770e108a1d5071e8baf45
- Base/start: d773e6bd198c0e07ee1fe038c130486982fbfdb7
- Commits reviewed: 4 code commits (`c1fb4691d` read view as one page scroll, `38bd44c66` ktlint format, `3f1d79a5f` tests and round-1 reports, `d868e21e2` CR-3 fix) plus board-only commits (assume, verify, heartbeat). The parent named HEAD `114b43f75`; while this review ran the branch was rebased onto the board commits `5f159cebe` and `2977cd64b` (verify #2 and #3). `git diff 114b43f75 d868e21e2` touches only `tasks/and-mail-read-fullheight.md`, so the reviewed code is identical to `114b43f75`. Working tree clean.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Round-1 findings: status

- CR-1 (criterion 7 had no tests): resolved. The committed tests cover what criterion 7 names: `MailReadScreenTest` checks the order headers, attachments, banner, reply buttons, body, links inside `PAGE`, that `PAGE` is the only vertical scroll outside the attachment list, that toolbar and previous/next row lie outside and above it, that a 2000dp body is not squeezed and fills the page after scrolling, that a short body leaves no gap before the links, that a long plain-text mail has no scroll of its own, and that the scroll position is per mail but kept when only images change. `MailReadNoteLinksTest` checks that 30 links are laid out at full height (no 240dp cap, no own scroll) and that the last one opens after `performScrollTo`. `MailWebViewTest` and `MailBodyViewTest` cover `mailBodyHeight`, the `onMeasure` override under UNSPECIFIED vs EXACTLY/AT_MOST, `keepHeight`/`pageFinished`/`forgetHeight`, the same-key vs other-key branch, and no reload on an equal document. The Robolectric workaround `layOutWebView` (sets `mTop`/`mBottom` by reflection) is test-only and documented; the logic under test runs unmocked.
- CR-2 (device-only criteria 2 and 3): resolved in the evidence. Criteria 2, 3 and 8 are recorded as skipped with concrete reasons (no emulator, Chromium measuring and touch sharing not available under Robolectric) and name what the owner should check on the phone. The zoom trade-off is recorded as an assumption.
- CR-3 (kept height survives a switch to another mail): fixed. `MailBodyView.update` now calls `view.forgetHeight()` when the key differs (`MailBodyView.kt:194`); `forgetHeight` clears `keptHeight` and requests a layout (`MailBodyView.kt:131`). The regression test `another mail loaded before the reload finished drops the kept height` would fail without the fix, since Robolectric never calls `onPageFinished` by itself.
- CR-4 (notice and progress bar fixed above the page): recorded as an assumption on the task; no code change needed.
- TW-1 (attachment list keeps its own 168dp scroll box): recorded as an assumption; `MailAttachments.kt` is outside the task's touches. The single-scroll test excludes only the attachment subtree, so a nested scroll anywhere else in the page still fails it.

## What was checked and holds

- Structure (criteria 1, 4, 5): `MailReadContent` keeps toolbar, navigation row, progress spacer and notice outside the scroll; the `PAGE` column uses `weight(1f)` inside the outer `Column` and `verticalScroll(key(message.id) { rememberScrollState() })`. `Message` is no longer a `ColumnScope` extension, has no `weight`, the plain text keeps `SelectionContainer` without its own `verticalScroll`, and the links column has neither `heightIn` nor a scroll of its own.
- Measuring: the WebView has MATCH_PARENT/WRAP_CONTENT layout params, no vertical scroll bar and no overscroll; under the unbounded page scroll Compose measures it with UNSPECIFIED height, and `onMeasure` only raises the height to the kept value in that mode, so a fixed or bounded parent is never overridden (tested).
- Height keep across "Load images": only the same key with a new document keeps the height; another key forgets it; an equal document neither reloads nor keeps. `pageFinished` and `forgetHeight` request a layout only when a height was kept.
- Lock-down (criterion 6): `lockDownForMail`, the CSP in `mailDocument`, `shouldInterceptRequest` with the `data:` pass-through and `shouldOverrideUrlLoading` are unchanged. The added `onPageFinished` override only touches the height (the base implementation is empty) and ignores a non-`MailWebView`.
- Callers: `MailBodyView` is only used by `MailReadRoute` (with `key = state.message?.id`) and as the default `body` of `MailReadContent` (no key; used where no body is passed, where treating every change as the same mail only keeps a height until the page finished).
- Imports, KDoc and naming match the surrounding code; no debug output, no logging of mail content, no new dependencies.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The remaining open points are device behaviour (Chromium content height after images load, pinch zoom and horizontal panning inside the page scroll), which the task records as skipped criteria 2, 3 and 8 for the owner's check on the phone. Criteria 1, 4, 5, 6, 7 and 9 still need their evidence recorded by the parent worker from the test runs.
