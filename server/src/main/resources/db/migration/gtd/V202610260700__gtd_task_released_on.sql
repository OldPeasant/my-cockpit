-- The day the tickler job moved a SCHEDULED task to Next (SPEC.md §5.1), so the Today view still shows it under
-- "Scheduled for today" (§5.3) after its scheduled date was cleared. Cleared when the task leaves Next.
alter table task
    add column released_on date;
