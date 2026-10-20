# Code Review

Date: 2026-10-20

Task: web-next-today - Next Actions and Today views

Scope: committed changes of task web-next-today, `git diff 75304fef..1567c2fb` (uncommitted test-writer changes ignored)

Requirements:
- tasks/web-next-today.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.7 (settings), §5.1-§5.4 (GTD concepts, UI, API)
- AGENTS.md project notes and conventions
- docs/ARCHITECTURE.md conventions (via AGENTS.md)
- Parent instructions (scope, already-run validation)

Git range:
- Branch: main
- HEAD: 1567c2fb261c4c91c27187e5b668141a88a518d0
- Base/start: 75304fefe9bd09af713ce71d797d40d6e99b32e3
- Commits reviewed: 6 (e1cb413b, e74a7152, c2af28ad, 16261bb5, 4c37d628, 1567c2fb)

Summary: 0 P0, 0 P1, 2 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Saving a preset while the settings are not loaded overwrites every stored preset**
    - File: `web/src/app/features/gtd/next/filter-presets.ts:37` (also `:26-29`, `:51-55`; `web/src/app/features/gtd/next/next-view.ts:142`)
    - Category: Correctness
    - Problem: `FilterPresets.save()` builds the full new list from `this.presets()` at call time. When the settings are not loaded (`settings()` is `null`), `presets()` is `[]`. This happens when the settings request failed: `NextView` swallows that error with `ensureLoaded().catch(() => undefined)` and still offers "Save filter…". The new list is then `[newPreset]`. `SettingsService.update()` loads the settings in `save()` (`previous = this.current() ?? await this.load()`), so it gets a fresh ETag, and the PATCH replaces the whole `nextActionPresets` list. The server accepts it, so every preset saved before is lost. `remove()` has the same read-modify-write problem.
    - Why it matters: Silent loss of user data (saved presets on every device) after one transient settings load failure. The spec test "still shows the next actions when the settings cannot be loaded" covers the view but not a save afterwards.
    - Suggested fix: Do not compute the list from unloaded settings. Either have `save`/`remove` `await this.ensureLoaded()` before reading `presets()` (and fail if that fails), or hide or disable the save and delete controls while `settings()` is `null`. Add a test: settings load fails, then a save sends the list that includes the existing server presets, or is refused.

- [ ] **CR-2 | "Scheduled for today" section can effectively never show a task (criterion #2 only partly met)**
    - File: `web/src/app/features/gtd/today/today-view.ts:76-84`, `:101`
    - Category: Correctness
    - Problem: The section loads `list=SCHEDULED` and keeps tasks with `scheduledDate <= today`. Before listing, the server runs `TaskService.releaseDue()` (`server/.../gtd/domain/TaskService.kt:235-239`). It moves every SCHEDULED task due today or earlier to NEXT and clears `scheduledDate`, so the answer only contains future-dated tasks, which the client filter drops. The only exception is a skew between browser and server dates. The task's own assumption says this and creates follow-up `tasks/gtd-scheduled-marker.md`, but the acceptance criterion "Today view: overdue, due today, scheduled today" is not met for users.
    - Why it matters: The Today view looks complete, but it never shows what SPEC.md §5.3 asks for ("scheduled for today"). If criterion #2 is recorded as verified without qualification, the evidence overstates the result.
    - Suggested fix: Keep the deferral but make it explicit. When recording criterion #2 with `tasks.py verify`, name the limitation and the follow-up `gtd-scheduled-marker` (or mark that part skipped with a reason), so the human reviewing assumptions can accept or reject the deferral. No code change is required in this task if the deferral is accepted.

## P3 Low

- [ ] **CR-3 | A failed preset deletion is silently undone**
    - File: `web/src/app/features/gtd/next/next-view.ts:210-212`
    - Category: Quality
    - Problem: `removePreset` catches and discards the error. `SettingsService.update` rolls the optimistic change back, so the preset reappears with no message. `savePreset` in the same component does show `presetError` on failure.
    - Why it matters: The user clicks delete, the chip disappears and then comes back without explanation (e.g. after a 412 from another device). This is inconsistent with the save path.
    - Suggested fix: Set an error message (e.g. reuse the `presetError` alert area or a snackbar) when the removal fails, and assert it in the existing "keeps the view usable when deleting a preset fails" test.

- [ ] **CR-4 | Changes outside the task's declared `touches`**
    - File: `web/src/app/features/gtd/gtd.routes.ts:4-9`, `web/src/app/shell/nav-sections.ts:26`, `web/src/app/app.spec.ts:55`, `server/src/main/kotlin/ch/sonensei/cockpit/gtd/infra/GtdSettings.kt`
    - Category: Integration
    - Problem: `touches` is `web/src/app/features/gtd/next, web/src/app/features/gtd/today`. The diff also edits the shared GTD routes file, whose own comment says "the GTD view tasks never edit this file", plus the shell nav, the app spec and the server `gtd` module. The changes are small and needed (no `/today` placeholder existed, and the settings API rejects unknown keys), but the board's overlap detection (AGENTS.md "Shared and generated files": "stay inside your task's touches") did not know about them.
    - Why it matters: Parallel tasks touching the shell nav, GTD routes or the server `gtd` module got no heads-up, so merge conflicts are more likely. The routes comment now contradicts practice.
    - Suggested fix: Record the extra files with `tasks.py assume` (or adjust the routes-file comment to say that new GTD sections add one line there). No functional change is needed.

## Review conclusion

Material issues found; fix before completing the task.

CR-1 is a data-loss path for saved presets and should be fixed. CR-2 needs honest criterion evidence and an accepted deferral rather than a code change. The rest of the work meets the criteria and is well tested:
- URL-backed filters and server query mapping;
- race-safe loading via `ListStore`;
- the extension-slot design;
- the server setting registration, which follows the documented `SettingDefinition` bean pattern, and the `Settings` OpenAPI schema allows additional properties, so no regeneration is needed.

No acceptance-criterion evidence is recorded yet (`tasks.py verify web-next-today` shows all four unticked). The parent worker must record it.
