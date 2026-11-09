---
title: Respond to invitations in the Android event editor
status: in_progress
owner: dell26/agent-1
deps: [and-cal-event-fields]
prio: 2
parent: cal-invitation-respond
lease_until: 2026-11-09T02:52:33Z
releases: 0
extended: 
hold: 
---
## Goal
Part of `cal-invitation-respond` (Accept, decline or tentatively accept invitations (web + Android)), which was split.
Goal: the Android event view/editor calls `POST /api/v1/calendar/events/{id}/respond` (SPEC §6.2), same rules as the web subtask (see the assumptions on cal-invitation-respond). Touches android/.../calendar/edit, its tests and strings_calendar_edit.xml (values, values-de) only.

## Acceptance criteria
- [x] Android event view/editor: Accept / Maybe / Decline with the current status; sending updates the status
  - verified (2026-11-09 00:15+0100 by felix/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*' -> pass (Accept/Maybe/Decline with current status selected; answering updates status/version)
- [x] New strings in English and German (`res/values{,-de}/strings_calendar_edit.xml`)
  - verified (2026-11-08 23:23+0100 by felix/agent-1): git diff 0cec3b4ba HEAD -- android/app/src/main/res/ -> 7 new calendar_edit_respond_* strings in values/ and values-de/strings_calendar_edit.xml (same keys, German uses du); lintDebug (MissingTranslation) passes in the helper run
- [ ] Unit tests cover the new behavior; `sh scripts/check.sh` passes

## Notes
- Work done on `cal-invitation-respond` before the split is on `origin/wip/cal-invitation-respond` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-11-08 23:11+0100 by felix/agent-1): Same rules as web (parent assumption #1): Accept/Maybe/Decline shown for existing events with guests in writable GOOGLE/MICROSOFT calendars; own status = guest matching a mail account address; 412 -> conflict (reload), 404 -> gone, other failures (409 organizer/not invited) -> 'could not be sent' message; unsaved form edits are kept after answering.
- release by felix/agent-1: Implementation, tests (tests.md), review (review.md: CR-1 P2, CR-2/CR-3 P3) and fixes (fixes.md: CR-1, CR-2 fixed at f33b74882; CR-3 skipped as optional, mail accounts fetched twice) are committed. Left: rerun test-writer + code-reviewer on f33b74882 (code changed after review), verify the 3 criteria, run sh scripts/check.sh, write quality.json (CR-3 accepted), done.
- release by felix/agent-1: All code, fixes, final test+review reports and quality.json committed (reviewed_revision b663ca0dc); AC1, AC2 verified. Left: AC3 needs sh scripts/check.sh to pass, which fails only on unrelated flaky auth.AppStartRoutingTest 'logging out from settings clears the cached files' (failed 4/4 runs under load). Re-run check (or wait for new task fix-flaky-app-start-logout-test, committed on this branch), then verify 3 and done.
- release by felix/agent-1: All code, final test/review reports and quality.json committed (reviewed_revision b663ca0dc); AC1, AC2 verified. Only AC3 + done left. 2026-11-09: sh scripts/check.sh: tfp, server and web sections passed, android section still running when the 20-min tool limit killed it (full check >20 min on this host). Next: run sh scripts/check.sh (or just the android part: cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug) with a long timeout, verify 3, then tasks.py done. Known flaky: auth.AppStartRoutingTest logout test (task fix-flaky-app-start-logout-test).
