---
title: Fix flaky ClockConfigurationTests (micro- vs nanosecond Instant compare)
status: todo
owner: 
deps: []
touches: [server/src/test/kotlin/ch/sonensei/cockpit/core/persistence]
prio: 2
lease_until: 
hold: Already fixed on main by 67652993 (cal-caldav, dell26/agent-2): ClockConfigurationTests truncates 'before' to MICROS, matching ClockConfiguration's Clock.tick(MICROS) floor, so before<=now<=after alwa
releases: 0
extended: 
---
## Goal

## Acceptance criteria
- [ ] 

## Notes
- hold by felix/agent-1: Already fixed on main by 67652993 (cal-caldav, dell26/agent-2): ClockConfigurationTests truncates 'before' to MICROS, matching ClockConfiguration's Clock.tick(MICROS) floor, so before<=now<=after always holds. Task has no acceptance criteria; please drop it. (Also: on host felix JAVA_HOME=/usr/lib/jvm/java-21-openjdk is missing, so gradle/.taskcheck cannot run here.)
