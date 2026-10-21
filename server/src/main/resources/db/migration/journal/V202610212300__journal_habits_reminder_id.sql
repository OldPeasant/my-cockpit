-- The pending core reminder the habits module manages for a habit (its reminder_time). Other reminders of the habit,
-- e.g. ones the user added through the reminders API, are left alone. No foreign key: reminders belong to core, and a
-- reminder that is gone (fired, deleted) only means the module creates a new one on the next move.
alter table habit add column reminder_id uuid;
