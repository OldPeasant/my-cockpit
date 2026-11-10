# Code Review

Date: 2026-11-10

Task: and-mail-read-fullheight - Android read view: mail at full height in one page scroll, linked items below

Scope: committed changes under `android/` from the task base `d773e6bd1` to HEAD (`git diff d773e6bd1..HEAD -- android`): `MailReadScreen.kt` and `MailBodyView.kt` in `android/app/src/main/java/ch/sonensei/cockpit/mail/read/`. Uncommitted test edits by the parallel test-writer (`MailBodyViewTest.kt`, `MailWebViewTest.kt`) are outside the reviewed scope. Static review only: Gradle was not run, as the parent asked (a test-writer runs Gradle in this clone at the same time).

Requirements:
- `tasks/and-mail-read-fullheight.md` acceptance criteria 1-9 and the recorded assumption (WebView wrap-content measuring, height kept during a same-mail reload, zoom trade-off)
- `SPEC.md` (read view, linked items panel on every detail view)
- `AGENTS.md` project notes (conventions, lint, test placement)
- Parent instructions (focus: AndroidView measure spec under an unbounded constraint, WRAP_CONTENT, height-keep on reload, per-message scroll state, lock-down unchanged, criteria coverage)

Git range:
- Branch: main
- HEAD: 179164d848d123ff5d21d66f6773abbdebe7d518
- Base/start: d773e6bd198c0e07ee1fe038c130486982fbfdb7
- Commits reviewed: 2 code commits (`70d384cd3` read view as one page scroll, `179164d84` ktlint format) plus board commits `1d9dcce47` (assume) and `b6d6202a1` (verify #8 skipped). The hashes named by the parent (`5903136be`, `983ccbf84`) are no longer on the branch; the history was rebased. The reviewed code diff is the one at HEAD.

Summary: 0 P0, 1 P1, 1 P2, 2 P3

## What was checked and holds

- Measure path: `verticalScroll` gives the page content an infinite max height; Compose's `AndroidViewHolder` reads the child's layout params (`getLayoutParams()` returns the WebView's), and with WRAP_CONTENT (or any non-fixed value) and an infinite max it measures the view with `MeasureSpec.UNSPECIFIED`. The width is MATCH_PARENT with a bounded max, so it is measured EXACTLY to the screen width. `MailWebView.onMeasure` only raises the height in UNSPECIFIED mode, so it cannot break a bounded parent. Setting `layoutParams` inside the factory goes through `WebView.setLayoutParams`, so Chromium sees the wrap-content height and switches to zero-layout-height mode (content does not stretch to the view height). `modifier.wrapContentHeight()` is a no-op under the scroll; it does no harm.
- Height keep: `keepHeight()` is only called when the document changes and the key is equal to the previous one (Load images, Always for this sender, retry), it holds `height` from the last layout, `onMeasure` takes `max(measured, kept)`, and `onPageFinished` (fired after the load event, so after the images) clears it and requests a layout. Two reloads in a row keep the larger height. `ImagesFailed` leaves the message unchanged, so it causes no reload.
- Scroll state per message: `key(message.id) { rememberScrollState() }` gives each mail its own (saveable) state; a same-mail update (images loaded, flag) keeps it. The view model also resets `message` to null on `open`, which disposes the page and WebView between mails.
- Structure (criterion 1, 4, 5): toolbar and navigation row stay outside the scroll; the scroll holds headers, attachments, images banner, reply buttons, divider, body (HTML via `body`, or `SelectionContainer` text without its own scroll) and the links column, in that order. The `heightIn(max = 240.dp)` cap and the links' inner `verticalScroll` are gone. `LinkedItemsPanel` is a plain `Column` (no lazy list, its search dialog's scroll lives in a dialog window), so it measures safely under an unbounded height.
- Lock-down (criterion 6): `lockDownForMail`, the CSP in `mailDocument`, `shouldInterceptRequest` (with the `data:` pass-through) and `shouldOverrideUrlLoading` are unchanged. The new `onPageFinished` override only touches the height; the base implementation is empty.
- Lint conventions: removed imports (`ColumnScope`, `heightIn`) and new ones match their use.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Criterion 7 has no tests in the reviewed revision**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:306`, `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt:117`
    - Category: Tests
    - Problem: the task commits change no test file. Nothing checks that one scroll container (`MailReadTags.PAGE`) holds headers, body and links. Nothing checks that the links column has no height cap or inner scroll, that a plain-text mail has no nested scroll, or that the scroll state is per message. Nothing tests the body height logic (`mailBodyHeight`, `MailWebView.keepHeight`/`pageFinished`, `onMeasure` raising the height only under UNSPECIFIED, the same-key vs other-key branch in `MailBodyView.update`). The existing `MailReadNoteLinksTest` "links below the body" ordering test still passes, but it would also pass with the old capped layout.
    - Why it matters: criterion 7 names exactly these tests. Without them, a later change can put back a `weight(1f)` body or a capped links box without any test failing.
    - Suggested fix: add Robolectric tests. Check that `PAGE` is an ancestor of `HEADERS`, `BODY`/`TEXT` and `LINKS` with a vertical scroll range (for example `SemanticsProperties.VerticalScrollAxisRange` on `PAGE` only, not on `LINKS` or `TEXT`). Check that many fake links all exist and that the last one comes into view with `performScrollTo`. Add unit tests for `mailBodyHeight` and for `MailWebView` measuring with UNSPECIFIED vs AT_MOST while a height is kept. The test-writer's uncommitted `MailBodyViewTest.kt` work goes in this direction. In its current working-tree state it uses `MutableState`, `mutableStateOf` and `Snapshot` without importing them (lines 3-20), so it does not compile yet. Re-review it once it is committed.

## P2 Medium

- [ ] **CR-2 | Full-height and zoom behaviour (criteria 2 and 3) can only be shown on a real WebView, and the manual check is skipped**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt:137`
    - Category: Tests
    - Problem: the approach depends on Chromium's own wrap-content sizing (content height times page scale, re-measured on image decode and zoom) and on how touches are shared between the Compose `verticalScroll` and the WebView. Compose's pointer interop gives the view its events in the Final pass, after the scroll's drag detection, unless the WebView calls `requestDisallowInterceptTouchEvent`. So a pinch whose first finger moves vertically past the slop may be taken by the page scroll and cancelled in the WebView. Robolectric's shadow WebView renders and measures nothing (the uncommitted tests set a fake 1800 px layout by hand). Criterion 8 (manual check) is recorded as skipped.
    - Why it matters: under AGENTS.md "Evidence", criteria 2 and 3 must not be ticked on unit-test evidence. Their main risks (clipped bottom or empty space after "Load images", pinch zoom taken over by the page scroll) can only be seen on a device.
    - Suggested fix: record criteria 2 and 3 as skipped with the same reason as #8 (no emulator or device), or as verified only for the parts the unit tests really show (for example kept height across a same-mail reload). In the skip note, ask the owner to check on the phone: pinch zoom on a wide newsletter, horizontal pan while zoomed, and the height after "Load images".

## P3 Low

- [ ] **CR-3 | A kept height is not cleared when another mail loads into the same WebView**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt:185`
    - Category: Correctness
    - Problem: `update` only calls `keepHeight()` for the same key. When the key changes, it does not reset a `keptHeight` left by an earlier, unfinished same-mail reload. If "Load images" is followed by another mail before `onPageFinished`, the new mail is measured at least at the old mail's height until its own page finishes, which can show a large white gap. In `MailReadRoute` this cannot happen today: `MailReadViewModel.reset` sets `message` to null, which disposes the WebView between mails. But `MailBodyView`'s KDoc for `key` promises the behaviour on its own.
    - Why it matters: the composable's contract depends on its caller disposing it between mails.
    - Suggested fix: when the key differs, clear the kept height before loading (for example a `forgetHeight()` or `keepHeight(false)`), and cover it in the "another mail" test.

- [ ] **CR-4 | Notice and progress bar stay in the fixed area above the page**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:300`
    - Category: Correctness
    - Problem: criterion 1 names only the toolbar and the previous/next row as fixed. The progress bar or spacer and `state.notice` (for example "Task created: ...", which stays while the mail is open) also sit outside the scroll. A visible notice therefore takes 1-2 lines of the mail area for the whole time the mail is open.
    - Why it matters: this is a small deviation from the wanted layout that was not recorded. Keeping status messages visible is a reasonable choice, but it should be a recorded decision.
    - Suggested fix: either record it with `tasks.py assume` (status stays visible while scrolling), or move `Notice` to the top of the scrolling page.

## Review conclusion

Material issues found; fix before completing the task. The layout change, the measure path and the lock-down look correct in static review. The open items are the missing criterion-7 tests (CR-1) and handling the device-only criteria 2 and 3 honestly in the evidence (CR-2). CR-3 and CR-4 are small and optional.
