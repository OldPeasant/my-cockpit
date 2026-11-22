# Code Review

Date: 2026-11-22

Task: web-cal-hidden-indicator - Web calendar: day/week show which entries are scrolled out of view

Scope: `git diff 44db0f76c..HEAD -- web` (hidden-entries.ts, hidden-entries.spec.ts, hidden-chips.ts, calendar-view.ts/html/scss, messages.xlf, messages.de.xlf). The test-writer's concurrent, still uncommitted files (calendar-view.spec.ts changes, hidden-chips.spec.ts, an e2e scratch spec) were not reviewed, as the parent asked.

Requirements:
- tasks/web-cal-hidden-indicator.md (acceptance criteria and recorded assumptions)
- AGENTS.md project notes (i18n, conventions)
- Existing calendar-view conventions (WCAG 2.5.3 comment on toolbar buttons)
- FullCalendar 7.1.3 time-grid layout in node_modules (checked how the scrollers are built)

Git range:
- Branch: main
- HEAD: d92382f27e0f30e38ae19b01406369958c6e149e
- Base/start: 44db0f76cfc74ae775589ab8efbb9e2d231aa846
- Commits reviewed: 4 (8cf62154b, 8818208fe, c0a1ad40d, 4bd88909f) plus merge d92382f27

Summary: 0 P0, 0 P1, 1 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Chips are not recomputed when the period changes, so stale chips from the previous period can stay on screen**
    - File: `web/src/app/features/calendar/views/calendar-view.ts:192`
    - Category: Correctness
    - Problem: The effect that schedules a chip update tracks only `timedEntries()`, `viewType()` and `settings()`. Going prev/next in the same view changes `range`, but not `viewType`. `OccurrenceStore` keeps the old `items` while the new range loads (`occurrence-store.ts:81-90`), and it leaves them in place if the load fails. Until the new items arrive, or forever if the load fails, nothing schedules an update unless a scroll or resize event happens. FullCalendar resetting the scroll to `scrollTime` only fires a scroll event if the scroll position actually changes. In that window the old chips stay on the new columns (same x positions, keys with the old dates) and point at the previous week's entries.
    - Why it matters: Criterion #1 says the chips must update "when the period or events change". The chips then show wrong counts while the new period loads, and keep showing them after a failed load.
    - Suggested fix: Also read `this.range()` in the scheduling effect, or call `scheduleChips()` from `onDatesSet`. Add a component test: navigate, keep the occurrences unchanged, and check that the chips are recomputed or cleared.

## P3 Low

- [ ] **CR-2 | The singular count reads ungrammatically in both languages**
    - File: `web/src/app/features/calendar/views/calendar-view.ts:231`, `web/src/locale/messages.de.xlf:2133`
    - Category: Quality
    - Problem: The labels are built as `${count} events earlier, show` / `${count} frühere Termine, anzeigen`, so a single hidden entry reads "1 events earlier, show" / "1 frühere Termine, anzeigen". Screen readers announce this label for every chip.
    - Why it matters: A count of 1 is the most common case, and the accessible name is wrong for it in both locales.
    - Suggested fix: Pick separate one/other messages in code (for example `calendar.hidden.aboveLabel.one`), because `$localize` in TS has no ICU plurals. Add the matching German translations.

- [ ] **CR-3 | The wide chip's visible text is not part of its accessible name (WCAG 2.5.3)**
    - File: `web/src/app/features/calendar/views/calendar-view.html:96`
    - Category: Quality
    - Problem: A wide chip shows "2 earlier" ("2 früher"), but `aria-label` replaces the name with "2 events earlier, show", which does not contain "2 earlier" as one piece of text. This component already follows 2.5.3 on purpose (`calendar-view.ts:130`: "The hint is the accessible name: it must contain the visible text").
    - Why it matters: Speech-input users who say the visible text may not be able to activate the chip. This is also inconsistent with the convention in the same file.
    - Suggested fix: Start the label with the visible text, for example "2 earlier: show 2 hidden events" / "2 früher: 2 verborgene Termine anzeigen". Or keep the visible text and add the extra wording through `aria-describedby`.

- [ ] **CR-4 | The deferred chip update is not cancelled when the component is destroyed**
    - File: `web/src/app/features/calendar/views/calendar-view.ts:197`
    - Category: Quality
    - Problem: The effect queues `setTimeout(() => this.scheduleChips())`, but `ngOnDestroy` (line 217) cancels only an animation frame that is already pending. If the view is destroyed before the timeout fires, the timeout still queues a new `requestAnimationFrame`, and `updateChips` then runs on the destroyed component, querying its detached DOM and setting a signal.
    - Why it matters: The work is wasted and can fire late in component tests that destroy the fixture. Today this causes no visible bug.
    - Suggested fix: Keep the timeout handle and clear it in `ngOnDestroy`, or set a `destroyed` flag that `scheduleChips` checks.

Notes (not findings):
- FullCalendar 7.1.3's time grid (`fullcalendar/chunks/08b6f884.js`, around lines 1371-1407) renders the column headers and the all-day lane outside the vertical body scroller. The all-day lane scrolls only horizontally. So placing the chips at the edges of the scroller that `timeGridOf` finds keeps them off the headers and the all-day row, as criterion #2 requires. This still needs the planned browser check at 360px.
- No acceptance criterion has recorded evidence yet (`tasks.py verify web-cal-hidden-indicator`). The real-browser checks for criteria #1-#4 are still open.
- The pure function `hiddenEntries` / `scrollTargetFor` / `daySpans` and its unit tests cover the boundaries well: partly visible entries, entries crossing midnight, an entry ending exactly at midnight, zero-duration entries, and nearest-entry selection regardless of order.

## Review conclusion

Material issues found; fix before completing the task. CR-1 means criterion #1 ("update when the period changes") is not fully met. CR-2 to CR-4 are minor.
