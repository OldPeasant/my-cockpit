-- Attachment metadata of a cached body (SPEC.md §7.2, §7.3): read together with the body, so reopening a message
-- needs no IMAP round trip. Evicted with its body (LRU of mail_body).
create table mail_body_attachments (
    message_id  uuid primary key references mail_body (message_id) on delete cascade,
    attachments jsonb not null
);
