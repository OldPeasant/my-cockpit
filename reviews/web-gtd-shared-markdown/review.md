# Code Review

Date: 2026-11-09

Task: web-gtd-shared-markdown - Web: share renderMarkdown and Markdown styles between project description and task notes

Scope: task commits 4b3972142, 05f12f9f0, dfc05f7c6 on base cc8fbe4c0 (`git diff cc8fbe4c0 HEAD -- web`); reviewed revision 03c090ae62e171febf6aec2308d2ee7e623b1cea (re-review after fixer commit dfc05f7c6)

Requirements:
- tasks/web-gtd-shared-markdown.md acceptance criteria and recorded assumption (re-export shim in gtd/projects)
- AGENTS.md project notes (feature folders, touches, lint conventions)
- Previous review findings CR-1/CR-2 and reviews/web-gtd-shared-markdown/fixes.md

Git range:
- Branch: main
- HEAD: 03c090ae62e171febf6aec2308d2ee7e623b1cea
- Base/start: cc8fbe4c0
- Commits reviewed: 3 (plus TFP claim/release/merge commits without code changes)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification notes

- AC1: `renderMarkdown` now lives in `web/src/app/features/gtd/ui/markdown.ts` and is exported from the
  `gtd/ui` barrel. `project-detail-page.ts` and `task-detail.ts` import it from `../ui`. No import from
  `gtd/projects` remains in `gtd/task-detail` (grep for `projects` in task-detail `*.ts` finds only the
  `ProjectStore` field). The function body is byte-identical to the old `gtd/projects/markdown.ts`
  apart from the doc and heading comments, which the fixer rewrote (`git diff --no-index` against the base
  version). `gtd/projects/markdown.ts` is a one-line re-export for bookmarks, files and contacts, which are
  outside the task's touches. This is recorded as an assumption and covered by the new export identity test in
  `gtd/ui/markdown.spec.ts`.
- AC2: `gtd/ui/markdown.scss` holds the same `.markdown` rules as both removed copies. The task-only
  `cursor: pointer` on links stays in `task-detail-notes.scss` as `.markdown a`. Both components list
  `../ui/markdown.scss` in `styleUrls`. I checked the cascade: the shared sheet now loads before
  `project-description.scss`/`task-detail-notes.scss`, but the co-applied classes (`.preview`,
  `.notes-view`) set no properties that `.markdown` also sets. `.notes-view > :first-child` (0,2,0)
  beats `.markdown h3`/`blockquote` (0,1,1) by specificity, as it did before. The rendered output therefore
  looks the same.
- Previous findings: CR-1 (styleUrls line length) is fixed, and `prettier --check` passes on all 9 changed web
  files. CR-2 (comment said it was only for project descriptions) is fixed.
- AC3: In this session `npm run check` did not finish within a 280 s timeout, so I could not confirm it here.
  The parent worker must record its own `npm --prefix web run check` and `sh scripts/check.sh` results as
  evidence. The release note mentions an android Compose timeout in `scripts/check.sh` that looked
  unrelated. That must be re-run, not assumed.

## Review conclusion

No material issues found.
