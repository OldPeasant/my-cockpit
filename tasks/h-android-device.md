---
title: HUMAN: real phone test
status: done
owner: Peter Gürtler
deps: [p3-smoke,and-apk-distribution]
quality: exempt
quality_reason: human device test, no code
hold: 
prio: 2
releases: 0
extended: 
lease_until: 
---
## Goal
Owner installs the APK and runs the (H) steps of phase 3.

## Acceptance criteria
- [x] Share target, widget, tile, offline capture and reminders work on the owner's phone
  - verified (2026-11-01 18:29+0100 by Peter Gürtler): it works

## Notes
- done by Peter Gürtler: `sh scripts/check.sh` passed in 235s on 21172db0e; criteria verified 1/1
