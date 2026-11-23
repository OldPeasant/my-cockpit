---
title: Android: Tickler list view (what comes back when)
status: in_progress
owner: dell26/agent-1
deps: [and-gtd-tickler-chip]
touches: [SPEC.md,android/app/src/main/java/ch/sonensei/cockpit/gtd/lists,android/app/src/test/java/ch/sonensei/cockpit/gtd/lists,android/app/src/main/java/ch/sonensei/cockpit/ui/navigation,android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt,android/app/src/main/res/values/strings_projects_lists.xml,android/app/src/main/res/values-de/strings_projects_lists.xml]
prio: 2
lease_until: 2026-11-23T18:14:42Z
---
## Goal
Tasks with a future tickler date are hidden from every list until that date (SPEC.md §5.1). The only place they show up
today is the calendar's task overlay, one day at a time. So the user can't check whether a task really is in the tickler
and when it will come back, which GTD's Weekly Review expects. Add a **Tickler** list view next to Scheduled (owner's decision,
2026-11-23). The web client gets the same in `web-gtd-tickler-view`.

## Acceptance criteria
- [ ] SPEC.md §5.3 lists the Tickler view: all open tasks with a tickler date after today, sorted by that date (then
      by their usual order), each showing the date it comes back
- [ ] Android has a "Tickler" / "Wiedervorlage" destination next to Scheduled (same navigation section, own icon),
      showing the tasks above with their return date ("Back on Mon, 14 Dec" / German equivalent). Tasks whose
      tickler date has come (moved to the Inbox) are not shown
- [ ] Tapping a row opens the task detail, where the date can be changed or another list chosen (form from
      `and-gtd-tickler-chip`); after returning, the view reflects the change. Rows support completing a task like the
      other list views do
- [ ] Pull to refresh, loading, empty ("Nothing in the tickler"), offline and error states follow the existing list
      screens (Scheduled, Someday, Waiting)
- [ ] Data comes from the existing API if possible: `GET /api/v1/tasks?from=&to=` already returns tasks still in
      a tickler. Filter on `ticklerDate` after today on the client. Only if that can't return all of them reliably
      (paging, open-ended range), add a server filter (e.g. `tickler=pending`), regenerate `api/openapi.yaml`, and
      record the decision with `tasks.py assume`
- [ ] All new strings exist in English and German; unit/Compose tests cover the criteria above;
      `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes (and `cd server && ./gradlew check`
      if the server changed)

## Notes
- Reference: `gtd/lists/ScheduledScreen.kt`, `ListViewModels.kt`, `TaskLists.kt`; navigation in
  `ui/navigation/CockpitDestination.kt` and `ui/CockpitShell.kt`; labels in `strings_projects_lists.xml`.
- Use the name "Tickler"/"Wiedervorlage", as decided in `and-gtd-tickler-chip`.
