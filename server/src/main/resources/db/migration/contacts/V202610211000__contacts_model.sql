-- Contacts (SPEC.md §8.3). emails, phones and addresses are JSON arrays of objects
-- (ch.sonensei.cockpit.contacts.domain.ContactEmail/ContactPhone/ContactAddress). photo_file_id points into the files
-- module, so it is no foreign key (like link item ids). external_id is the id at the source (GOOGLE, MICROSOFT, ...).
create table contact (
    id            uuid primary key,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    version       bigint      not null,
    deleted_at    timestamptz,
    display_name  text        not null,
    given_name    text,
    family_name   text,
    emails        jsonb       not null default '[]',
    phones        jsonb       not null default '[]',
    addresses     jsonb       not null default '[]',
    organization  text,
    title         text,
    birthday      date,
    notes_md      text,
    photo_file_id uuid,
    source        text        not null,
    external_id   text,
    constraint contact_source check (source in ('LOCAL', 'GOOGLE', 'MICROSOFT', 'CARDDAV'))
);

create index contact_display_name on contact (display_name, id) where deleted_at is null;
create unique index contact_external_id on contact (source, external_id) where external_id is not null;
