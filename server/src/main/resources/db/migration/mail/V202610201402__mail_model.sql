-- Mail data model (SPEC.md §7.1–7.2): accounts, sending identities, folders, cached message headers and bodies.
-- Enum columns store the Kotlin enum names (ch.sonensei.cockpit.mail.model).

-- oauth_connection_id points into core OAuth connections; no foreign key, so mail does not depend on that table
-- (a disconnect leaves the account without usable credentials, which the IMAP layer reports).
create table mail_account (
    id                  uuid primary key,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    version             bigint      not null,
    type                text        not null,
    display_name        text        not null,
    email_address       text        not null,
    imap_host           text        not null,
    imap_port           integer     not null,
    imap_security       text        not null,
    smtp_host           text        not null,
    smtp_port           integer     not null,
    smtp_security       text        not null,
    username            text        not null,
    -- AES-256-GCM ciphertext (EncryptedSecret), IMAP_SMTP accounts only.
    password            text,
    oauth_connection_id uuid,
    signature_md        text,
    notify_new_mail     boolean     not null default true,
    sync_window_months  integer     not null default 12,
    constraint mail_account_type check (type in ('IMAP_SMTP', 'GOOGLE', 'MICROSOFT')),
    constraint mail_account_imap_security check (imap_security in ('SSL_TLS', 'STARTTLS', 'NONE')),
    constraint mail_account_smtp_security check (smtp_security in ('SSL_TLS', 'STARTTLS', 'NONE')),
    constraint mail_account_imap_port check (imap_port between 1 and 65535),
    constraint mail_account_smtp_port check (smtp_port between 1 and 65535),
    constraint mail_account_sync_window check (sync_window_months > 0),
    -- Password accounts never carry an OAuth connection, OAuth accounts never a password.
    constraint mail_account_credentials check (
        (type = 'IMAP_SMTP' and oauth_connection_id is null) or (type <> 'IMAP_SMTP' and password is null)
    )
);

create table mail_identity (
    id           uuid primary key,
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    version      bigint      not null,
    account_id   uuid        not null references mail_account (id) on delete cascade,
    name         text        not null,
    email        text        not null,
    reply_to     text,
    -- Overrides the account signature when set.
    signature_md text,
    is_default   boolean     not null default false,
    position     integer     not null default 0
);

create unique index mail_identity_email on mail_identity (account_id, lower(email));
create unique index mail_identity_default on mail_identity (account_id) where is_default;

create table mail_folder (
    id               uuid primary key,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    version          bigint      not null,
    account_id       uuid        not null references mail_account (id) on delete cascade,
    -- Full IMAP path (e.g. INBOX/Projects), unique per account; name is its last segment.
    path             text        not null,
    name             text        not null,
    delimiter        text,
    role             text,
    -- true when the owner set role by hand; SPECIAL-USE detection then leaves it alone.
    role_overridden  boolean     not null default false,
    selectable       boolean     not null default true,
    uidvalidity      bigint,
    uidnext          bigint,
    highestmodseq    bigint,
    last_synced_at   timestamptz,
    constraint mail_folder_role check (role in ('INBOX', 'SENT', 'DRAFTS', 'TRASH', 'ARCHIVE', 'SPAM'))
);

create unique index mail_folder_path on mail_folder (account_id, path);
create unique index mail_folder_role_per_account on mail_folder (account_id, role) where role is not null;

-- Cached headers. A row is the link target of an EMAIL item (SPEC.md §4.3). It is located (the message is at
-- folder_id/uid on the server) only when both folder_id and uid are non-null. uid is null while the message is not
-- (or no longer) at that location, and resolvers then find it again by account + message_id_header.
-- A folder deletion keeps the row for the same reason: folder_id becomes null and any uid left behind is stale and
-- means nothing (so the uid check below cannot require a folder, or the cascade would fail).
create table mail_message (
    id                 uuid primary key,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    version            bigint      not null,
    account_id         uuid        not null references mail_account (id) on delete cascade,
    folder_id          uuid        references mail_folder (id) on delete set null,
    uid                bigint,
    modseq             bigint,
    message_id_header  text,
    in_reply_to        text,
    references_header  text,
    thread_key         text,
    subject            text,
    from_addresses     text,
    to_addresses       text,
    cc_addresses       text,
    bcc_addresses      text,
    reply_to_addresses text,
    sent_at            timestamptz,
    received_at        timestamptz,
    seen               boolean     not null default false,
    flagged            boolean     not null default false,
    answered           boolean     not null default false,
    draft              boolean     not null default false,
    -- Other IMAP keywords ($Forwarded, $Junk, user labels).
    keywords           text[]      not null default '{}',
    size_bytes         bigint      not null default 0,
    has_attachments    boolean     not null default false,
    snippet            text,
    constraint mail_message_uid check (uid is null or uid > 0),
    constraint mail_message_size check (size_bytes >= 0)
);

create unique index mail_message_folder_uid on mail_message (folder_id, uid) where uid is not null;
create index mail_message_folder_received on mail_message (folder_id, received_at desc, id desc) where uid is not null;
create index mail_message_account_received on mail_message (account_id, received_at desc, id desc) where uid is not null;
create index mail_message_message_id on mail_message (account_id, message_id_header) where message_id_header is not null;
create index mail_message_thread on mail_message (account_id, thread_key) where thread_key is not null;

-- Body cache (LRU, size-bounded, SPEC.md §7.2): one row per fetched message body. size_bytes is the UTF-8 size of
-- text_plain + text_html, the unit of the cache budget; last_accessed_at orders eviction.
create table mail_body (
    id               uuid primary key,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    version          bigint      not null,
    message_id       uuid        not null references mail_message (id) on delete cascade,
    text_plain       text,
    text_html        text,
    size_bytes       bigint      not null,
    last_accessed_at timestamptz not null,
    constraint mail_body_size check (size_bytes >= 0)
);

create unique index mail_body_message on mail_body (message_id);
create index mail_body_lru on mail_body (last_accessed_at, id);
