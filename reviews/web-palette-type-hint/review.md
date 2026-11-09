# Code Review

Date: 2026-11-09

Task: web-palette-type-hint - Web: make the Ctrl+K type: filter discoverable (hint or chips in the palette)

Scope: task commits on top of base e2321d948 up to final revision d76dce30d (`git diff e2321d948 d76dce30d`); re-review of the test-writer addition `git diff 2cdfe9088 d76dce30d -- web`

Requirements:
- tasks/web-palette-type-hint.md acceptance criteria
- SPEC.md §4.4 (command palette), docs/ARCHITECTURE.md conventions (i18n, styling tokens, shell/feature boundaries)
- AGENTS.md project notes
- reviews/web-small-gaps/review.md CR-2 (origin of the task)

Git range:
- Branch: main
- HEAD: d76dce30d (reviewed revision)
- Base/start: e2321d9485b4b97a38298f3e61d22a8899ca1e36
- Commits reviewed: 2 (2cdfe9088 implementation, d76dce30d test-writer test + reports)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Acceptance criteria check

1. Hint line: `command-palette.html` shows a `.type-hint` paragraph below the results with examples
   (`type:note passport`, `type:task,event`). It hides once the query contains a word starting with
   `type:` (case-insensitive). That matches how `parseSearchQuery` finds the filter (trimmed,
   whitespace-split, `toLowerCase().startsWith('type:')`). The shell duplicates the regex instead of
   importing it from `features/search`, which keeps the shell independent of feature code. Met.
2. i18n: `@@palette.typeHint` is in `messages.xlf` and `messages.de.xlf` with matching placeholders.
   The German examples `type:notiz Reisepass` / `type:aufgabe,termin` resolve through the localized
   `itemTypeLabel` targets (`Notiz`, `Aufgabe`, `Termin`), so they work in the German build. The changed
   line ranges for `palette.empty` match the template. Met.
3. Tests: the new spec covers the hint showing initially, staying for plain text, hiding for `type:`
   at the start (mixed case) and in the middle, and staying for `subtype:x`. The check commands were
   not re-run in this review. `tasks.py verify` showed no recorded evidence yet, so the parent worker
   still has to record it.

Re-review at d76dce30d: the only code change since 2cdfe9088 is one added test in
`web/src/app/shell/command-palette.spec.ts` ("shows the type: hint again after the filter is removed,
also with results listed"). It uses the existing `type`/`labels` helpers and checks real behavior:
the hint hides for `  type:note` (leading whitespace), comes back after the query is cleared, and stays
visible next to a non-empty result list (`inb` -> `Inbox`). It asserts on rendered DOM, not on internals,
and does not weaken any existing test. No new findings. The prior findings still apply.

Styling uses project tokens (`--app-space-*`, `--app-border`, `--app-fg-muted`, `--mat-sys-body-small`).
I found no security concerns (static text, no user input rendered).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Hint is not linked to the query field for screen readers**
    - File: `web/src/app/shell/command-palette.html:51`
    - Category: Quality
    - Problem: The hint is a plain `<p>` after the listbox, with no `id` and no `aria-describedby` from the combobox input. Focus stays in the input, so screen reader users never hear the hint.
    - Why it matters: The task exists to make the filter discoverable. Right now it is only discoverable visually.
    - Suggested fix: Give the paragraph an id (e.g. `app-palette-type-hint`) and bind `[attr.aria-describedby]="showTypeHint() ? 'app-palette-type-hint' : null"` on the input.

- [ ] **CR-2 | Hint disappears for an unrecognized `type:` word**
    - File: `web/src/app/shell/command-palette.ts:48`
    - Category: Quality
    - Problem: `showTypeHint` hides as soon as any word starts with `type:`, including `type:` alone or `type:foo`. `parseSearchQuery` treats those as plain search text, so the filter silently does nothing, and the examples are gone just when the user needs them.
    - Why it matters: This is a small usability gap for a mistyped type name. Nothing breaks.
    - Suggested fix: Optional. Hide only when the query actually filters, e.g. by moving the visibility check into a small exported helper in `features/search` (if the shell may depend on it) or by keeping the hint visible until the `type:` word has a non-empty value. Leaving it as is would also be acceptable.

## Review conclusion

No material issues found.
