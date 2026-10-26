package ch.sonensei.cockpit.perf

import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Seeds the performance baseline data set (docs/perf.md) with plain SQL, in about 10 s: [TASKS] tasks, [NOTES] notes,
 * [MAIL_HEADERS] cached mail headers and [EVENTS] calendar events, plus their search documents.
 *
 * Text is drawn from [VOCABULARY] with a skewed distribution (the first words are frequent, the last ones rare), so a
 * query for [COMMON_WORD] matches most items: the worst case for ranking. [RARE_WORD] is in every 500th title only.
 * Dates are relative to today, so date-window queries always hit data. `setseed` makes every run produce the same data.
 * All data is fictitious.
 */
class PerfSeed(
	private val jdbc: JdbcTemplate,
) {
	data class Seeded(
		val contextId: UUID,
		val projectId: UUID,
		val accountId: UUID,
		val inboxFolderId: UUID,
	)

	fun seed(): Seeded {
		jdbc.execute(
			ConnectionCallback { connection ->
				connection.createStatement().use { statement ->
					// One connection, so setseed applies to every random() of the seed.
					statement.execute("select setseed(0.42)")
					seedStatements().forEach(statement::execute)
				}
			},
		)
		jdbc.execute("analyze")
		return Seeded(
			contextId = uuid("select id from context order by name limit 1"),
			projectId = uuid("select id from project order by title limit 1"),
			accountId = uuid("select id from mail_account order by email_address limit 1"),
			inboxFolderId =
				uuid(
					"select f.id from mail_folder f join mail_account a on a.id = f.account_id " +
						"where f.role = 'INBOX' order by a.email_address limit 1",
				),
		)
	}

	fun clean() {
		listOf(
			"search_document",
			"link",
			"task_context",
			"checklist_item",
			"task",
			"project",
			"context",
			"area",
			"note",
			"mail_account",
			"calendar_source",
		).forEach { jdbc.execute("delete from $it") }
	}

	private fun uuid(sql: String): UUID = jdbc.queryForObject(sql, UUID::class.java)!!

	private fun seedStatements(): List<String> {
		val words = VOCABULARY.joinToString(",") { "'$it'" }

		// text(n): n words drawn with a cubic skew towards the start of the vocabulary. Correlated with the row (g), so
		// Postgres evaluates it per row.
		fun text(count: String) =
			"(select string_agg((array[$words])[1 + floor(power(random(), 3) * ${VOCABULARY.size})::int], ' ') " +
				"from generate_series(1, $count) where g is not null)"
		return listOf(
			"""
			create temporary table perf_tag (n int, name text)
			""",
			"""
			insert into area (id, created_at, updated_at, version, title, position)
			select gen_random_uuid(), now(), now(), 0, 'Area ' || g, g from generate_series(1, 5) g
			""",
			"""
			insert into context (id, created_at, updated_at, version, name, position)
			select gen_random_uuid(), now(), now(), 0, '@context' || lpad(g::text, 2, '0'), g from generate_series(1, 10) g
			""",
			"""
			insert into project (id, created_at, updated_at, version, title, area_id, status)
			select gen_random_uuid(), now(), now(), 0, 'Project ' || lpad(g::text, 3, '0') || ' ' || ${text("3")},
			  (select id from area order by random() + g * 0 limit 1),
			  case when g % 10 = 0 then 'COMPLETED' when g % 7 = 0 then 'SOMEDAY' else 'ACTIVE' end
			from generate_series(1, $PROJECTS) g
			""",
			// 40 % done (Logbook), the open ones spread over the lists; every 3rd task in a project.
			"""
			insert into task (id, created_at, updated_at, version, title, notes_md, list, project_id, position, energy,
			  time_estimate_min, due_date, scheduled_date, tickler_date, completed_at)
			select gen_random_uuid(), ts, ts, 0,
			  case when g % 500 = 0 then '$RARE_WORD ' else '' end || ${text("4 + g % 6")},
			  case when g % 3 = 0 then ${text("10 + g % 40")} end,
			  case when g % 10 < 3 then 'NEXT' when g % 10 < 5 then 'INBOX' when g % 10 < 7 then 'SOMEDAY'
			    when g % 10 < 9 then 'WAITING' else 'SCHEDULED' end,
			  case when g % 3 = 0 then (select id from project order by title offset (g / 3) % $PROJECTS limit 1) end,
			  g,
			  (array['LOW', 'MEDIUM', 'HIGH'])[1 + g % 3],
			  case when g % 4 = 0 then 5 * (1 + g % 24) end,
			  case when g % 5 = 0 or g % 10 = 9 then current_date + (g % 120 - 60) end,
			  case when g % 10 = 9 then current_date + 1 + g % 60 end,
			  case when g % 50 = 1 then current_date + 1 + g % 90 end,
			  case when g % 5 < 2 then ts + interval '2 days' end
			from (select g, now() - (g * interval '1 hour') * random() * 2 as ts from generate_series(1, $TASKS) g) s
			""",
			"""
			insert into task_context (task_id, context_id)
			select t.id, c.id from (select id, row_number() over (order by id) as n from task) t
			join (select id, row_number() over (order by name) as n from context) c
			  on c.n = 1 + t.n % 10 or (t.n % 4 = 0 and c.n = 1 + (t.n / 4) % 10)
			""",
			"""
			insert into perf_tag (n, name) select g, (array[${TAGS.joinToString(",") { "'$it'" }}])[g]
			from generate_series(1, ${TAGS.size}) g
			""",
			"""
			insert into note (id, created_at, updated_at, version, title, body_md, pinned, archived)
			select gen_random_uuid(), ts, ts, 0,
			  case when g % 500 = 0 then '$RARE_WORD ' else '' end || ${text("2 + g % 5")},
			  ${text("50 + g % 300")}, g % 20 = 0, g % 10 = 3
			from (select g, now() - (g * interval '4 hours') * random() * 2 as ts from generate_series(1, $NOTES) g) s
			""",
			"""
			insert into note_tag (note_id, tag)
			select n.id, t.name from (select id, row_number() over (order by id) as n from note) n
			join perf_tag t on t.n = 1 + n.n % ${TAGS.size} or (n.n % 3 = 0 and t.n = 1 + (n.n / 3) % ${TAGS.size})
			""",
			"""
			insert into mail_account (id, created_at, updated_at, version, type, display_name, email_address, imap_host,
			  imap_port, imap_security, smtp_host, smtp_port, smtp_security, username)
			select gen_random_uuid(), now(), now(), 0, 'IMAP_SMTP', 'Account ' || g, 'jane' || g || '@example.org',
			  'imap.example.org', 993, 'SSL_TLS', 'smtp.example.org', 465, 'SSL_TLS', 'jane' || g
			from generate_series(1, 2) g
			""",
			"""
			insert into mail_folder (id, created_at, updated_at, version, account_id, path, name, role, uidvalidity)
			select gen_random_uuid(), now(), now(), 0, a.id, f.path, f.path, f.role, 1
			from mail_account a
			cross join (values ('INBOX', 'INBOX'), ('Archive', 'ARCHIVE'), ('Sent', 'SENT')) f (path, role)
			""",
			// 60 % Inbox, 30 % Archive, 10 % Sent; conversations of 3 messages; 80 % read; two years of mail.
			"""
			insert into mail_message (id, created_at, updated_at, version, account_id, folder_id, uid, message_id_header,
			  thread_key, subject, from_addresses, to_addresses, sent_at, received_at, seen, flagged, size_bytes, snippet)
			select gen_random_uuid(), ts, ts, 0, a.id, f.id, g, '<' || g || '@mail.example.org>', 'thread-' || g / 3,
			  case when g % 500 = 0 then '$RARE_WORD ' else '' end || ${text("3 + g % 8")},
			  'Sender ' || g % 300 || ' <sender' || g % 300 || '@example.com>', a.email_address, ts, ts,
			  g % 5 <> 0, g % 40 = 0, 2000 + g % 50000, ${text("30")}
			from (select g, now() - interval '730 days' * random() as ts from generate_series(1, $MAIL_HEADERS) g) s
			join (select id, email_address, row_number() over (order by email_address) - 1 as n from mail_account) a
			  on a.n = g % 2
			join mail_folder f on f.account_id = a.id
			  and f.role = case when g % 10 < 6 then 'INBOX' when g % 10 < 9 then 'ARCHIVE' else 'SENT' end
			""",
			"""
			insert into calendar_source (id, created_at, updated_at, version, type, name)
			values (gen_random_uuid(), now(), now(), 0, 'LOCAL', 'Local')
			""",
			"""
			insert into calendar (id, created_at, updated_at, version, source_id, name, is_default)
			select gen_random_uuid(), now(), now(), 0, s.id, 'Calendar ' || g, g = 1
			from calendar_source s cross join generate_series(1, 3) g
			""",
			// Two years around today: 10 % all-day, every 50th a weekly series.
			"""
			insert into calendar_event (id, created_at, updated_at, version, calendar_id, title, description, location,
			  all_day, start_at, end_at, time_zone, start_date, end_date, rrule, status, transparency, sync_state)
			select gen_random_uuid(), now(), now(), 0,
			  (select id from calendar order by name offset g % 3 limit 1),
			  case when g % 500 = 0 then '$RARE_WORD ' else '' end || ${text("2 + g % 5")},
			  case when g % 2 = 0 then ${text("20")} end, case when g % 4 = 0 then 'Room ' || g % 20 end,
			  g % 10 = 0,
			  case when g % 10 <> 0 then day + interval '8 hours' + (g % 10) * interval '1 hour' end,
			  case when g % 10 <> 0 then day + interval '9 hours' + (g % 10) * interval '1 hour' end,
			  case when g % 10 <> 0 then 'Europe/Zurich' end,
			  case when g % 10 = 0 then day::date end,
			  case when g % 10 = 0 then day::date + 1 + g % 3 end,
			  case when g % 50 = 1 then 'FREQ=WEEKLY;COUNT=' || (10 + g % 40) end,
			  'CONFIRMED', 'OPAQUE', 'LOCAL'
			from (select g, date_trunc('day', now()) + ((g * 7919) % 730 - 365) * interval '1 day' as day
			  from generate_series(1, $EVENTS) g) s
			""",
			"drop table perf_tag",
			"""
			insert into search_document (item_type, item_id, title, body, updated_at)
			select 'TASK', id, title, coalesce(notes_md, ''), updated_at from task
			""",
			"""
			insert into search_document (item_type, item_id, title, body, updated_at)
			select 'NOTE', id, coalesce(title, ''), body_md, updated_at from note
			""",
			"""
			insert into search_document (item_type, item_id, title, body, updated_at)
			select 'EMAIL', id, coalesce(subject, ''), from_addresses || ' ' || to_addresses || ' ' || snippet, updated_at
			from mail_message
			""",
			"""
			insert into search_document (item_type, item_id, title, body, updated_at)
			select 'EVENT', id, title, coalesce(description, ''), updated_at from calendar_event
			""",
		).map { it.trimIndent() }
	}

	companion object {
		const val TASKS = 10_000
		const val NOTES = 2_000
		const val MAIL_HEADERS = 50_000
		const val EVENTS = 5_000
		const val PROJECTS = 150

		val TAGS = listOf("work", "home", "ideas", "reading", "travel", "finance", "health", "recipes", "family", "archive")

		val VOCABULARY =
			listOf(
				"meeting",
				"project",
				"report",
				"review",
				"team",
				"plan",
				"client",
				"budget",
				"update",
				"call",
				"draft",
				"schedule",
				"office",
				"invoice",
				"order",
				"design",
				"release",
				"contract",
				"travel",
				"lunch",
				"quarterly",
				"planning",
				"proposal",
				"feedback",
				"agenda",
				"deadline",
				"launch",
				"marketing",
				"sales",
				"customer",
				"support",
				"server",
				"website",
				"newsletter",
				"workshop",
				"training",
				"conference",
				"presentation",
				"summary",
				"minutes",
				"approval",
				"payment",
				"receipt",
				"delivery",
				"shipment",
				"supplier",
				"inventory",
				"warehouse",
				"garden",
				"kitchen",
				"repair",
				"plumber",
				"insurance",
				"doctor",
				"dentist",
				"appointment",
				"birthday",
				"holiday",
				"vacation",
				"flight",
				"hotel",
				"booking",
				"ticket",
				"concert",
				"museum",
				"library",
				"book",
				"article",
				"research",
				"analysis",
				"dashboard",
				"metrics",
				"forecast",
				"strategy",
				"roadmap",
				"milestone",
				"sprint",
				"backlog",
				"issue",
				"bug",
				"feature",
				"migration",
				"database",
				"backup",
				"security",
				"password",
				"account",
				"subscription",
				"renewal",
				"tax",
				"accountant",
				"bank",
				"mortgage",
				"lease",
				"apartment",
				"moving",
				"furniture",
				"groceries",
				"recipe",
				"dinner",
				"party",
				"wedding",
				"gift",
				"school",
				"teacher",
				"homework",
				"exam",
				"course",
				"lecture",
				"seminar",
				"thesis",
				"paper",
				"journal",
				"editor",
				"publisher",
				"translation",
				"interview",
				"candidate",
				"hiring",
				"onboarding",
				"offboarding",
				"salary",
				"bonus",
				"expense",
				"reimbursement",
				"audit",
				"compliance",
				"policy",
				"handbook",
				"process",
				"checklist",
				"template",
				"spreadsheet",
				"photo",
				"video",
				"podcast",
				"episode",
				"camera",
				"laptop",
				"printer",
				"network",
				"router",
				"phone",
				"charger",
				"bicycle",
				"car",
				"garage",
				"tires",
				"parking",
				"train",
				"station",
				"airport",
				"visa",
				"passport",
				"embassy",
				"neighbor",
				"community",
				"volunteer",
				"charity",
				"donation",
				"fundraiser",
				"festival",
				"market",
				"harvest",
				"orchard",
				"vineyard",
				"mountain",
				"lake",
				"hiking",
				"skiing",
				"swimming",
				"running",
				"yoga",
				"gym",
				"nutrition",
				"vitamins",
				"pharmacy",
				"prescription",
			)

		const val COMMON_WORD = "meeting"
		const val MEDIUM_WORD = "release"
		const val RARE_WORD = "zanzibar"
		const val TYPO_WORD = "zanzibra"
	}
}
