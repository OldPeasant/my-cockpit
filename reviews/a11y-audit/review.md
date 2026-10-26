# Code Review

Date: 2026-10-26

Task: a11y-audit - Accessibility audit

Scope: full task diff `git diff 89d9bd404 HEAD -- . ':!tasks' ':!reviews'` (8 commits). The task was rebased from base b5757334f onto 89d9bd404. Between those two bases nothing outside `tasks/` and `reviews/` differs, and `git diff 94756fbf0 c83fecac3` is empty for `web`, `docs`, `server`, `android` and `api`. So the first 7 rebased commits are the same code reviewed before (previous hashes 9727243fc..94756fbf0). The only new code is 5f95566b7, a test-only commit. This pass reviewed it in full.

Requirements:
- tasks/a11y-audit.md: the acceptance criterion and its five recorded assumptions (scan scope; fixes outside `touches`; the FullCalendar scroller DOM patch; the trash label-in-name change; the markdown editor HighlightStyle with the `@lezer/highlight` dependency)
- SPEC.md, docs/ARCHITECTURE.md (stack table, "Install"), AGENTS.md project notes (lint, one-line build-file additions, slow suites kept out of `.taskcheck`)
- Previous review of this task (CR-1, CR-2) and `reviews/a11y-audit/fixes.md`
- Parent and test-writer evidence, not re-run by the reviewer:
  - full Playwright e2e suite (`npm --prefix web run e2e`, podman stack) on 94756fbf0: 69 passed
  - focused specs: 191 passed
  - `npm --prefix web run coverage`: 3726 passed
  - lint clean

Git range:
- Branch: main
- HEAD: 5f95566b708143898c2e222f85b86e7950830f48
- Base/start: 89d9bd404f5101c17b8f6eaf8942a771e55aca60 (merge-base with origin/main; previously b5757334f before the rebase)
- Commits reviewed: 8 (22837de53, 0749fcb23, 49acf3cea, bb15780fc, e070975ef, 6d994b33a, c83fecac3, 5f95566b7); no uncommitted changes

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Status of earlier findings

- CR-1 (`focusableScrollers` had no unit test): still resolved. The calendar-view.spec.ts tests "keyboard access to the time grid" are unchanged since the earlier review.
- CR-2 (the patch skipped non-overflowing scrollers): still resolved. The `scrollHeight <= clientHeight` guard is still gone in `calendar-view.ts` `focusableScrollers`.

## Checks on the later commits (no findings)

- Trash label-in-name (`web/src/app/features/trash/trash-page.ts:156-160`):
  - The button's visible text is "Delete permanently" (`trash-page.html:71`, no icon text). The new aria-label `Delete permanently: {item title}` starts with that text, so it meets WCAG 2.5.3. "Restore {item title}" already met it.
  - The i18n id `@@trash.purgeAria` is kept. `src/locale/messages.xlf` has no `trash.*` units at all, so it was already stale before this task and nothing in it is broken by the change.
- Who relied on the old label:
  - `grep -rn "permanently" web/src web/e2e` finds no remaining use of `Delete {item title} permanently`. All uses in `trash-page.spec.ts` were updated.
  - In e2e, `phase1/demo.spec.ts:370` uses only `Restore {item title}`. `phase2/demo.spec.ts` and `files-browser/browser.spec.ts` check the trash through the API, not through the button name.
- a11y spec seeding (`web/e2e/a11y/a11y.spec.ts:53-56`, `289-295`):
  - One task is created and trashed, so `/trash` is scanned with a populated row.
  - `deleteTask` reuses the `If-Match` and XSRF handling that `emptyInbox` already used, and `emptyInbox` now calls it.
  - The trashed task has a unique per-run title. The only other e2e spec that reads trash rows by name (`phase1` test 9) filters by its own title, so no other spec is affected.
- Markdown HighlightStyle (`web/src/app/features/notes/editor/markdown-editor.ts:48-91`, `225`):
  - It covers the same tag set as CodeMirror's `defaultHighlightStyle`. The literal colors are replaced by tokens that exist in `web/src/theme/_theme.scss` (`--app-fg-muted`, `--app-accent`, `--app-danger`) and by Material system tokens.
  - The non-color cues are kept: headings are bold and underlined, links underlined, emphasis italic, strikethrough struck through.
  - `fallback: true` was dropped. That is correct, because this is now the editor's only highlighter: `markdown()` has no nested `codeLanguages` and no other `syntaxHighlighting` exists in `web/src`.
- Dependency:
  - `@lezer/highlight` `^1.2.5` is added as a single line to `web/package.json` and as the root entry in `package-lock.json`.
  - The installed version is 1.2.5, already present as a transitive dependency of `@codemirror/language`, so no new package is downloaded.
  - The ARCHITECTURE.md stack row is updated, as AGENTS.md requires.
- Unit tests (c83fecac3):
  - `markdown-editor.spec.ts` checks the real CodeMirror token spans against the injected CSS rules: accent color for URLs, muted color for marks, no literal `#`/`rgb` colors, and the bold/underline/italic cues.
  - `trash-page.spec.ts` adds a generic label-in-name test for every row action.
  - Both tests check behavior, not internals.
- Test-only commit 5f95566b7 (`markdown-editor.spec.ts:394-409`):
  - Purpose: it renders a richer note (heading, blockquote, list, strong, inline code, image URL, horizontal rule, inline HTML).
  - Guard: before checking anything it waits for the `tent` token's bold style, so the parse has been applied.
  - Main check: no rule targeting any styled span in the editor uses a literal `#`/`rgb` color. The regex is a substring match, so it also catches `background-color` and `rgba`, which makes the check stricter, not weaker.
  - Strength: the `styled.length > 3` floor keeps the loop from passing on an empty set.
  - Scope: no production code changed, so production behavior is unaffected.

Notes (not findings):
- The scan scope, theme handling, and running `a11y/` in the `inbox-exclusive` project match the recorded assumptions. Humans review those assumptions.
- The criterion describes running behavior (Playwright + axe against the stack, plus a keyboard walkthrough).
  - The reported full e2e run (69 passed) was on 94756fbf0, which has the same production code as HEAD; 5f95566b7 changes only a Vitest unit spec. So that run is valid evidence for the criterion on this revision.
  - Unit tests alone would not satisfy it.

## Review conclusion

No material issues found.
