---
title: Android: in-app capture button on every screen
status: in_progress
owner: dell26/agent-2
deps: [and-capture-entrypoints,and-shell]
touches: [android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/main/java/ch/sonensei/cockpit/capture/entry,android/app/src/test/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/capture]
prio: 2
lease_until: 2026-11-01T14:04:07Z
---
## Goal
On the web, quick capture is a button on every screen; on Android it is only reachable from outside the app (widget,
quick-settings tile, app shortcut, share). SPEC.md §2 "Quick capture must be reachable from every screen". Add an
in-app entry that opens the same capture sheet as `capture/entry/CaptureActivity` (offline queue, SPEC.md §9.3).

## Acceptance criteria
- [ ] Every top-level screen of the app shell shows a capture button ("+" FAB or app-bar action, consistent across screens); screens that already have their own FAB (Notes, Files, Mail, Calendar) keep it and get the capture action in the app bar instead, so there are never two FABs
- [ ] The button opens the capture sheet (text, notes, attachment as in the widget's sheet); saving writes to the offline capture queue and works in airplane mode; the pending count updates
- [ ] Closing the sheet returns to the screen the user was on
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone. Out of scope: changes to widget, tile, shortcut or share target behavior.
- ASSUMPTION (2026-11-01 14:02+0100 by dell26/agent-2): In-app capture button launches the existing CaptureActivity (same translucent sheet as widget/tile/shortcut) in the app's own task instead of re-implementing the sheet inside the shell; finishing it returns to the current screen. The widget's sheet has no attachment picker, so the in-app sheet has none either (criterion says 'as in the widget's sheet').
- ASSUMPTION (2026-11-01 14:02+0100 by dell26/agent-2): Placement: '+' FAB on every shell screen except Calendar, Mail, Notes, Files (CockpitDestination.withOwnFab), which get an app-bar action instead. The pending count is shown as a badge on the button and in its content description; the widget keeps its own count.
- ASSUMPTION (2026-11-01 14:17+0100 by dell26/agent-2): To keep the shell's new capture FAB from covering the Undo snackbar (review CR-1), the shell provides a FAB clearance via a CompositionLocal in ui/ and the gtd screens' SnackbarHosts (Today, Next, lists, Projects; outside this task's touches) pad by it: one-line edits, the smallest fix that keeps snackbars owned by their screens.
