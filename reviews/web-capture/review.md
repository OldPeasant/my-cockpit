# Code Review

Date: 2026-10-21

Task: web-capture - Quick capture dialog

Scope: committed changes of task web-capture, `git diff f7d1544d..HEAD` (the code commits named by the parent, ee979a48 and a6dc826b, are now 4134689b and 785dd09d after a rebase). The uncommitted change to `web/src/app/features/capture/capture-dialog.spec.ts` in the working tree is not reviewed. The Playwright suite was not run, because the parent is running it.

Requirements:
- tasks/web-capture.md acceptance criteria and its recorded assumptions
- SPEC.md section 4.5 (quick capture)
- docs/ARCHITECTURE.md (web conventions, i18n, theme tokens, testing)
- AGENTS.md project notes
- web/src/app/features/README.md (CAPTURE_DIALOG contract), shell/shell-tokens.ts, shell/shell.ts
- Generated API models (`CaptureRequest.id` is the client UUIDv7 for an idempotent capture)

Git range:
- Branch: main
- HEAD: 785dd09deb68b254003fef0c76b9ba59029c760f
- Base/start: f7d1544daa95970efbc3d5b14f45243c1ad0ad67
- Commits reviewed: 6 (2 code commits: 4134689b dialog, 785dd09d e2e spec; 4 `assume:` commits that only touch tasks/web-capture.md)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Preview shows the previous capture's result while a new text is typed**
    - File: `web/src/app/features/capture/capture-dialog.ts:56` (pipeline) and `web/src/app/features/capture/capture-dialog.html:35` (rendering)
    - Category: Correctness
    - Problem: `preview` keeps the last server response until the next debounced request comes back, and the template shows it whenever `text().trim()` is non-empty. After Shift+Enter, `text` is set to `''`, but if the user starts typing the next capture within `PREVIEW_DEBOUNCE_MS`, the empty value never gets past `debounceTime`. The parsed title and tokens of the capture that was just saved stay on screen next to the new text until the new preview arrives (at least 250 ms plus the round trip). The same lag shows tokens the user has already deleted while editing, for example a removed `#project`.
    - Why it matters: the criterion asks for a live syntax preview. For a moment the preview shows a reading that does not match the current text, which is most confusing in the "save and add another" flow.
    - Suggested fix: tie the preview to the text it was computed for. One way is to emit `{ text, response }` and render only when `response.text === text().trim()`, showing the hint or a pending state otherwise. Another is to reset the preview to `null` as soon as the text changes, for example with `startWith(null)` inside the `switchMap`, or a separate signal cleared in the input handler.

- [ ] **CR-2 | No test covers an out-of-order preview response**
    - File: `web/src/app/features/capture/capture-dialog.spec.ts` (committed version, `describe('preview')`)
    - Category: Tests
    - Problem: the preview tests only show that debouncing collapses quick typing into one request. No test starts a preview request, changes the text so a second request starts, and then answers the first, older request last. The implementation is correct today, because `switchMap` drops the older inner observable. But nothing guards that ordering, and a change to `mergeMap` or a manual subscription would not be caught.
    - Why it matters: the project review checklist treats stale async results overwriting newer ones as regression-prone, and the GTD stores in this codebase have explicit tests for it.
    - Suggested fix: add a spec that types A, waits past the debounce, types B, waits past the debounce, flushes B and then A, and asserts that the preview shows B's title.

Checked and found in order:
- Criterion 1: debounced, silent `capture/preview`; optional notes (empty notes left out); the paste/drop placeholder never sends files; Enter saves and closes; Shift+Enter saves, clears and refocuses; IME composition is respected; Ctrl/Cmd+Enter in notes is a recorded assumption.
- Criterion 2: the RFC 9562 layout in `uuidv7.ts` is correct (48-bit ms, version 7, variant 10, `crypto.getRandomValues`). The draft id is kept across a failed save and replaced only after success. The `saving` guard blocks a double submit.
- Criterion 3: Vitest covers preview, save, double submit, retry with the same id and the placeholder. `npm run check` was not run in this review.
- Architecture: the dialog stays inside `features/capture`, lazy loaded via `CAPTURE_DIALOG`, and uses the generated client through `CaptureService`. Strings are i18n-tagged, styles use theme tokens with no hard-coded colors, and save errors are reported once by the global `problemInterceptor`.
- Security: no unsafe HTML binding, file contents are never read or sent, and nothing sensitive is logged.

## Review conclusion

No material issues found.
