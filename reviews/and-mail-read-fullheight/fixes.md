# Fix Report

Date: 2026-11-10

Task: and-mail-read-fullheight (mail read view as one page scroll, mail body at full content height)

Scope: current task; only finding CR-3 from reviews/and-mail-read-fullheight/review.md, as instructed by the parent worker.

## Fixed findings

### CR-3 — A kept height is not cleared when another mail loads into the same WebView

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailBodyViewTest.kt`

Problem:
The `update` block of `MailBodyView` called `keepHeight()` only when the same key reloaded, and did nothing for a different key. A height kept by an unfinished same-mail reload (for example "Load images") therefore stayed in force when another mail loaded into the same `MailWebView` before `onPageFinished`, so the new mail was measured at least at the old mail's height. This contradicted the KDoc of the `key` parameter. Confirmed in the current code before changing it.

Fix:
Added `MailWebView.forgetHeight()`, which resets `keptHeight` to 0 and calls `requestLayout()` when a height was kept. The `update` block now calls `view.keepHeight()` for the same key and `view.forgetHeight()` otherwise, before loading the new document. The class KDoc mentions `forgetHeight`. Added the regression test "another mail loaded before the reload finished drops the kept height": same key reload keeps 1800, then a different key with new html loads the new document and leaves `keptHeight` at 0. Without the fix this test would see 1800, because Robolectric does not call `onPageFinished` by itself.

Validation:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` — Pass (BUILD SUCCESSFUL; MailBodyViewTest 7 tests, 0 failures; no failures in any mail.read result file)

## Not fixed

### CR-1 — missing criterion-7 tests

Status:
Out of scope

Reason:
Parent worker states it is resolved by the test-writer's tests; not part of this fixer run.

### CR-2 — device-only criteria 2 and 3

Status:
Out of scope

Reason:
Parent worker handles it in the evidence by skipping these criteria with a reason; no code change.

### CR-4 — notice and progress bar stay in the fixed area above the page

Status:
Requires decision

Reason:
Parent worker records it as an assumption; no code change requested.

### TW-1 — test-writer finding

Status:
Requires decision

Reason:
Parent worker records it as an assumption; no code change requested.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` — Pass

Full test suite:
- Not run (parent worker runs `.taskcheck` before completion)

Lint/type-check/build:
- `ktlintFormat` and `ktlintCheck` — Pass (no reformatting needed beyond the edits)

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0 code findings in this fixer's scope (CR-1, CR-2, CR-4, TW-1 handled by the parent worker as stated above)

## Final status

All validated findings fixed and validation passed.
