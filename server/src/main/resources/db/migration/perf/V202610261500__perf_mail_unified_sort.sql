-- Performance baseline (docs/perf.md): the unified Inbox (GET /api/v1/mail/messages without folder or account) spans
-- the Inbox folders of all accounts, which neither per-folder nor per-account sort index covers, so Postgres read and
-- sorted every cached header. With the list order on its own, a page is read in index order and only filtered by folder.
create index mail_message_sort on mail_message (coalesce(received_at, created_at) desc, id desc) where uid is not null;
