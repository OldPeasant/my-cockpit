# Code Review

Date: 2026-11-09

Task: web-gtd-shared-markdown - Web: share renderMarkdown and Markdown styles between project description and task notes

Scope: `git diff 9fef9ab2..HEAD -- web/` (task commit 7bc5ae606)

Requirements:
- tasks/web-gtd-shared-markdown.md acceptance criteria and recorded assumption
- AGENTS.md project notes (feature folders, lint contract)
- web/.prettierrc (printWidth 100)

Git range:
- Branch: main
- HEAD: 7bc5ae606
- Base/start: 9fef9ab231227319cfa1125e69041983ed1038d9
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | New styleUrls line exceeds the Prettier print width**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:96`
    - Category: Quality
    - Problem: The changed `styleUrls: ['./controls.scss', './task-detail.scss', '../ui/markdown.scss', './task-detail-notes.scss'],` line is longer than 100 characters; `npx prettier --check` flags the file. Prettier is not part of `npm run check`, and the file already has other over-long lines, so nothing fails.
    - Why it matters: It breaks the project formatting convention, and the next `prettier --write` will produce an unrelated diff.
    - Suggested fix: Wrap the array one entry per line, the way `project-detail-page.ts` does.

- [ ] **CR-2 | Shared renderMarkdown doc comment still says it is for project descriptions**
    - File: `web/src/app/features/gtd/ui/markdown.ts:1-2` (also the heading comment at line 29)
    - Category: Quality
    - Problem: The comment still says "for previews of short texts (a project's description)" and "Headings of a description". The function is now the shared renderer in `gtd/ui`, used by task notes too (and, through the re-export, by bookmarks, files and contacts).
    - Why it matters: A small inaccuracy that hides who depends on the function.
    - Suggested fix: Reword it as general short-text Markdown, e.g. "project descriptions, task notes, ...".

## Verification notes

- AC1: `renderMarkdown` now lives in `gtd/ui/markdown.ts` and is exported from `gtd/ui/index.ts`. Both `project-detail-page.ts` and `task-detail.ts` import it from `../ui`. A grep finds no import from `gtd/projects` in `gtd/task-detail/*.ts`. The implementation matches the old file exactly (same blob `dd8859d3d`), and the spec file was moved unchanged. `projects/markdown.ts` stays as a re-export for bookmarks, files and contacts. That is recorded as an assumption and is outside this task's touches.
- AC2: `gtd/ui/markdown.scss` is the same as the old duplicated `.markdown` block. Both components list it in `styleUrls` before their own sheets. The only difference between the two copies, `cursor: pointer` on note links, is kept as a `.markdown a` override in `task-detail-notes.scss`, which loads after the shared sheet and has the same specificity. `.preview` and `.notes-view`, which sit on the same elements, set no properties that `.markdown` also sets, so the cascade order does not change how anything renders.
- AC3: the parent reports that `npm run check` passed. I did not run it again.

## Review conclusion

No material issues found.
