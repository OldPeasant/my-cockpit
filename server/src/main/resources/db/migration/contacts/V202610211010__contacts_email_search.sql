-- The contact list's q filter: true when one of the email addresses (only the address values, not the labels or the
-- JSON keys around them) is like the given lower-case pattern, with '\' as the escape character.
create function contacts_email_matches(emails jsonb, pattern text) returns boolean
    language sql
    immutable
as
$$
select exists (select 1 from jsonb_array_elements(emails) e where lower(e ->> 'address') like pattern escape '\')
$$;
