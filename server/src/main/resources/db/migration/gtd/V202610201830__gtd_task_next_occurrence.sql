-- The next occurrence created when a recurring task was completed (SPEC.md §5.2). Completing the task again after
-- uncompleting it creates no second one.
alter table task
    add column next_occurrence_id uuid references task (id) on delete set null;
