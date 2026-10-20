# Code Review

Date: 2026-10-21

Task: web-next-today - Next Actions and Today views

Scope: re-review of the final code of task web-next-today, `git diff bdbabd7cbc0620f6772e352e0dcba394e3043ef7..724ac0da8aa3c1c996436d438b2b38d2ffb77b86` (commits prefixed "web-next-today:" plus `tasks: add gtd-scheduled-marker`). Committed changes only. The working tree was clean.

Requirements:
- tasks/web-next-today.md (acceptance criteria and recorded assumptions)
- tasks/gtd-scheduled-marker.md (follow-up for the deferred CR-2)
- SPEC.md §5.3 (Next Actions filters and presets, Today view), §4.7 (settings)
- AGENTS.md project notes, docs/QUALITY.md
- Previous review (CR-1..CR-4), reviews/web-next-today/fixes.md, reviews/web-next-today/tests.md
- Parent instructions: scope, the app.spec.ts rebase conflict resolution, Playwright already run by the parent

Git range:
- Branch: main
- HEAD: 724ac0da8aa3c1c996436d438b2b38d2ffb77b86
- Base/start: bdbabd7cbc0620f6772e352e0dcba394e3043ef7
- Commits reviewed: 9 (f1967796, 6c2eaf1c, 8cebcf86, 59b19ec3, cd3f5365, 689d648a, 0483bde5, 8144ede5, 724ac0da)

Commands run:
- `git diff bdbabd7c..724ac0da` and full reads of the changed files: `next/filter-presets.ts`, `next/next-filter.ts`, `next/next-view.ts`, `next/next-view.html`, `today/today-view.ts`, `today/today-view.html`, `today/today-sections.ts`, the routes files, `shell/nav-sections.ts`, `app.spec.ts`, `GtdSettings.kt`, the server tests, `e2e/gtd-next/next.spec.ts`, and `features/settings/settings.service.ts` for context
- `cd web && npm run check` (api:check, eslint, e2e typecheck, Vitest, ng build) -> pass. The build warns that the initial bundle is over budget (609 kB against 500 kB). That warning comes from code outside this task.
- `python tasks.py verify web-next-today` (read-only listing) -> #4 verified, #1-#3 not recorded yet

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of the previous findings

- **CR-1 (P2), presets wiped when saving with unloaded settings: fixed.** `FilterPresets.save()` and `remove()` (`web/src/app/features/gtd/next/filter-presets.ts:41-55`) now `await this.ensureLoaded()` before they read `presets()`. A failed load rejects before any PATCH is sent, and `NextView.savePreset` and `removePreset` show an error. `SettingsService.save` (`settings.service.ts:58`) takes `current()` as its base, so the list is built from loaded settings. Covered by filter-presets.spec ("refuses to save or delete while the settings cannot be loaded…", "loads the settings before saving…") and next-view.spec ("does not wipe the stored presets when saving after the settings failed to load").
- **CR-2 (P2), "Scheduled for today" is effectively always empty: deferred, accepted.** The code is unchanged (`today-view.ts:76-84`). The limitation is recorded as an assumption, and follow-up `tasks/gtd-scheduled-marker.md` exists with the right deps and touches. When recording criterion #2, the parent must still name this limitation and the follow-up in the evidence.
- **CR-3 (P3), failed delete silently undone: fixed.** `removePreset` (`next-view.ts:206-215`) sets "The preset could not be deleted. Try again." The alert below the chips (`next-view.html:46-48`) shows while the save form is closed. The existing test checks the alert text and checks that opening the form clears it.
- **CR-4 (P3), files outside `touches`: resolved through the recorded assumption.** The assumption lists gtd.routes.ts, nav-sections.ts, app.spec.ts and GtdSettings.kt. No code change is needed.

## Rebase conflict in app.spec.ts

`web/src/app/app.spec.ts:55-65`: the BUILT list now has this task's `/today` and `/next` plus the other tasks' `/waiting`, `/someday`, `/scheduled` and `/logbook`, with no duplicates or losses. The "coming soon" parametrized test excludes them correctly, and `npm run check` passes.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-5 | Recorded e2e evidence names a revision from before the preset fixes**
    - File: `tasks/web-next-today.md:18` (criterion #4 evidence); the code it depends on is `web/src/app/features/gtd/next/filter-presets.ts:41-55`
    - Category: Tests
    - Problem: The evidence for criterion #4 says the spec ran "at 75bd7412". That is before 8144ede5, which changed `FilterPresets.save()` and `remove()` (now async, loading first). The e2e spec runs both paths (save preset, delete preset). The parent says the spec passed again (6 passed), but the recorded evidence does not name the final revision.
    - Why it matters: A human reading the evidence can't tell whether the final preset code passed the browser test.
    - Suggested fix: When recording the final evidence, re-record criterion #4 with the revision the e2e run actually used (the final one, 724ac0da or its rebased equivalent). If the last run was before 8144ede5, run it again first.

## Review conclusion

No material issues found.

CR-1 and CR-3 are fixed correctly and have regression tests. CR-2 is deferred to the existing follow-up gtd-scheduled-marker, and CR-4 is covered by an assumption. The app.spec.ts conflict resolution is correct, and `npm run check` passes on the final code. CR-5 is about evidence only, not code. Before `done`, the parent still has to record criteria #1-#3 with `tasks.py verify` (and CR-2's limitation in #2).
