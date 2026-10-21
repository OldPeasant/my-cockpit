package ch.sonensei.cockpit.gtd.backup

import ch.sonensei.cockpit.backup.RestoreTestDatabases
import ch.sonensei.cockpit.backup.Zips
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

/**
 * [GtdBackupContributor] (SPEC.md §5, §10.2): the data files (`data/{areas,contexts,projects}.json`,
 * `data/{tasks,checklist_items}.jsonl`), the readable Markdown mirrors (`readable/tasks/<list>.md`,
 * `readable/projects/<area>/<project>.md`) and the restore of rows, references and search documents.
 *
 * Rows are inserted with JDBC so ids, times and versions are fixed and the orderings are under the test's control.
 * Runs against a database of its own, because every test empties all tables.
 */
@SpringBootTest
class GtdBackupContributorTests(
	@Autowired private val backups: BackupService,
	@Autowired private val contributor: GtdBackupContributor,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
) {
	@TempDir lateinit var work: Path

	@BeforeEach
	fun wipe() = RestoreTestDatabases.wipe(jdbc, dataDir)

	// --- export: data/ ----------------------------------------------------------------------------------------------

	@Test
	fun `tasks jsonl holds every task with its fields, sorted context ids and next occurrence, the Trash included`() {
		val errands = context(id(3), "@errands")
		val phone = context(id(2), "@phone", color = "#3366ff")
		val launch = project(id(10), "Launch")
		val next = task(id(21), "Water the plants", "SCHEDULED", scheduledDate = LocalDate.parse("2026-10-27"))
		task(
			id(20),
			"Water the plants",
			"SCHEDULED",
			projectId = launch,
			contexts = listOf(errands, phone),
			scheduledDate = LocalDate.parse("2026-10-20"),
			dueDate = LocalDate.parse("2026-10-20"),
			dueTime = LocalTime.parse("14:30"),
			recurrenceRule = "FREQ=WEEKLY;BYDAY=TU",
			recurrenceMode = "FIXED",
			nextOccurrence = next,
			completedAt = Instant.parse("2026-10-20T07:00:00.123456Z"),
			energy = "HIGH",
			creativity = "CREATIVE",
			estimate = 15,
			notes = "Both balconies",
			version = 4,
		)
		task(id(22), "Old idea", "SOMEDAY", deletedAt = T2)

		val tasks = export().tasks()

		tasks.map { it["id"].asString() } shouldContainExactly listOf(id(20), id(21), id(22)).map(UUID::toString)
		val done = tasks[0]
		// Only the record's own fields (absent ones may be null or left out by the canonical JSON).
		done.propertyNames().toSet() -
			setOf(
				"id",
				"createdAt",
				"updatedAt",
				"version",
				"deletedAt",
				"title",
				"notesMd",
				"list",
				"projectId",
				"position",
				"energy",
				"creativity",
				"timeEstimateMin",
				"contextIds",
				"dueDate",
				"dueTime",
				"scheduledDate",
				"ticklerDate",
				"waitingForContactId",
				"waitingForText",
				"waitingSince",
				"recurrenceRule",
				"recurrenceMode",
				"nextOccurrenceId",
				"completedAt",
			) shouldBe emptySet()
		done["contextIds"].toList().map { it.asString() } shouldContainExactly listOf(phone, errands).map(UUID::toString)
		done["nextOccurrenceId"].asString() shouldBe next.toString()
		done["projectId"].asString() shouldBe launch.toString()
		done["list"].asString() shouldBe "SCHEDULED"
		done["recurrenceRule"].asString() shouldBe "FREQ=WEEKLY;BYDAY=TU"
		done["recurrenceMode"].asString() shouldBe "FIXED"
		done["completedAt"].asString() shouldBe "2026-10-20T07:00:00.123456Z"
		done["createdAt"].asString() shouldBe "2026-03-01T08:15:30.123456Z"
		done["version"].asLong() shouldBe 4
		done["energy"].asString() shouldBe "HIGH"
		done["timeEstimateMin"].asInt() shouldBe 15
		tasks[1]["contextIds"].toList().shouldBeEmpty()
		tasks[1].isNullOrAbsent("nextOccurrenceId") shouldBe true
		tasks[2]["deletedAt"].asString() shouldBe "2026-03-03T10:00:00Z"
	}

	@Test
	fun `projects, areas, contexts and checklist items are exported with their fields, next steps and Trash state`() {
		val work = area(id(1), "Work", position = 3)
		context(id(2), "@phone", color = "#3366ff", position = 1)
		val launch =
			project(
				id(10),
				"Launch",
				area = work,
				description = "# Goal",
				dueDate = LocalDate.parse("2026-12-01"),
				reviewDate = LocalDate.parse("2026-11-01"),
			)
		project(id(11), "Abandoned", status = "DROPPED", deletedAt = T2)
		val draft = task(id(20), "Draft", "NEXT", projectId = launch)
		nextStep(launch, draft)
		checklistItem(id(31), draft, "Pricing table", done = false, position = 1)
		checklistItem(id(30), draft, "Hero text", done = true, position = 0)

		val zip = export()

		val projects = zip.json("data/projects.json").toList()
		projects.map { it["id"].asString() } shouldContainExactly listOf(id(10), id(11)).map(UUID::toString)
		projects[0]["nextTaskId"].asString() shouldBe draft.toString()
		projects[0]["areaId"].asString() shouldBe work.toString()
		projects[0]["status"].asString() shouldBe "ACTIVE"
		projects[0]["descriptionMd"].asString() shouldBe "# Goal"
		projects[0]["dueDate"].asString() shouldBe "2026-12-01"
		projects[0]["reviewDate"].asString() shouldBe "2026-11-01"
		projects[0].isNullOrAbsent("deletedAt") shouldBe true
		projects[1]["deletedAt"].asString() shouldBe "2026-03-03T10:00:00Z"
		projects[1].isNullOrAbsent("nextTaskId") shouldBe true

		val area = zip.json("data/areas.json").single()
		area["title"].asString() shouldBe "Work"
		area["position"].asInt() shouldBe 3
		val context = zip.json("data/contexts.json").single()
		context["name"].asString() shouldBe "@phone"
		context["color"].asString() shouldBe "#3366ff"
		context["position"].asInt() shouldBe 1

		val items = zip.lines("data/checklist_items.jsonl")
		items.map { it["text"].asString() } shouldContainExactly listOf("Hero text", "Pricing table")
		items.map { it["taskId"].asString() }.toSet() shouldBe setOf(draft.toString())
		items[0]["done"].asBoolean() shouldBe true

		zip.counts["areas"] shouldBe 1
		zip.counts["contexts"] shouldBe 1
		zip.counts["projects"] shouldBe 2
		zip.counts["tasks"] shouldBe 1
		zip.counts["checklist_items"] shouldBe 2
	}

	@Test
	fun `an empty GTD exports empty data files and empty list mirrors, and imports again`() {
		val zip = export()

		listOf("data/areas.json", "data/contexts.json", "data/projects.json").forEach { zip.json(it).toList().shouldBeEmpty() }
		zip.lines("data/tasks.jsonl").shouldBeEmpty()
		zip.lines("data/checklist_items.jsonl").shouldBeEmpty()
		zip.readable("tasks/inbox.md") shouldBe "# Inbox\n\n_No tasks._\n"
		zip.readable("tasks/next.md") shouldBe "# Next\n\n_No tasks._\n\n## Scheduled\n\n_No tasks._\n"
		zip.readable("tasks/waiting.md") shouldBe "# Waiting For\n\n_No tasks._\n"
		zip.readable("tasks/someday.md") shouldBe "# Someday/Maybe\n\n_No tasks._\n"
		zip.readable("tasks/logbook.md") shouldBe "# Logbook\n\n_No tasks._\n"
		zip.readablePaths().filter { it.startsWith("projects/") }.shouldBeEmpty()

		wipe()
		import(zip.path)
		listOf("area", "context", "project", "task", "checklist_item").forEach { withClue(it) { count(it) shouldBe 0 } }
	}

	// --- readable: tasks/ -------------------------------------------------------------------------------------------

	@Test
	fun `each list mirror holds its open tasks by position, without done or trashed ones`() {
		task(id(20), "Second", "INBOX", position = 1)
		task(id(21), "First", "INBOX", position = 0)
		task(id(22), "Done already", "INBOX", completedAt = T1)
		task(id(23), "Thrown away", "INBOX", deletedAt = T2)
		task(id(24), "Learn Esperanto", "SOMEDAY")
		task(id(25), "  ", "INBOX", position = 2)

		val zip = export()

		zip.readable("tasks/inbox.md") shouldBe "# Inbox\n\n- [ ] First\n- [ ] Second\n- [ ] (untitled)\n"
		zip.readable("tasks/someday.md") shouldBe "# Someday/Maybe\n\n- [ ] Learn Esperanto\n"
		zip.readablePaths().forEach { path -> withClue(path) { zip.readable(path) shouldNotContain "Thrown away" } }
	}

	@Test
	fun `next md lists scheduled tasks in a section of their own, soonest first`() {
		task(id(20), "Second", "NEXT", position = 1)
		task(id(21), "First", "NEXT", position = 0)
		task(id(22), "Later", "SCHEDULED", position = 0, scheduledDate = LocalDate.parse("2026-11-20"))
		task(id(23), "Sooner", "SCHEDULED", position = 5, scheduledDate = LocalDate.parse("2026-11-01"))
		task(id(24), "Done scheduled", "SCHEDULED", scheduledDate = LocalDate.parse("2026-10-01"), completedAt = T1)

		val next = export().readable("tasks/next.md")

		next shouldBe
			"""
			|# Next
			|
			|- [ ] First
			|- [ ] Second
			|
			|## Scheduled
			|
			|- [ ] Sooner
			|  Scheduled: 2026-11-01
			|- [ ] Later
			|  Scheduled: 2026-11-20
			|
			""".trimMargin()
	}

	@Test
	fun `waiting md shows whom a task waits for and since when`() {
		task(
			id(20),
			"Mockups",
			"WAITING",
			waitingForText = "Anna (design)",
			waitingSince = LocalDate.parse("2026-10-10"),
			waitingForContact = UUID.fromString("01890a5d-ac96-774b-bcce-b302099a8057"),
		)
		task(id(21), "Signed contract", "WAITING", position = 1, waitingSince = LocalDate.parse("2026-10-12"))

		export().readable("tasks/waiting.md") shouldBe
			"# Waiting For\n\n- [ ] Mockups\n  Waiting for: Anna (design) · Since: 2026-10-10\n- [ ] Signed contract\n  Since: 2026-10-12\n"
	}

	@Test
	fun `a task's details, notes and checklist are written below its checkbox`() {
		val phone = context(id(2), "@phone")
		val errands = context(id(3), "errands")
		val launch = project(id(10), "Launch")
		val draft =
			task(
				id(20),
				"Draft the\nlanding page",
				"NEXT",
				projectId = launch,
				contexts = listOf(phone, errands),
				dueDate = LocalDate.parse("2026-11-15"),
				dueTime = LocalTime.parse("14:30"),
				tickler = LocalDate.parse("2027-01-10"),
				recurrenceRule = "FREQ=WEEKLY;INTERVAL=6",
				recurrenceMode = "AFTER_COMPLETION",
				estimate = 90,
				energy = "HIGH",
				notes = "Sections:\n\n- hero\n- pricing\n\n",
			)
		checklistItem(id(30), draft, "Hero text", done = true, position = 0)
		checklistItem(id(31), draft, "Pricing\ntable", done = false, position = 1)

		export().readable("tasks/next.md") shouldBe
			"""
			|# Next
			|
			|- [ ] Draft the landing page
			|  Project: Launch · @phone @errands · Due: 2026-11-15 14:30 · Hidden until: 2027-01-10 · Repeats: FREQ=WEEKLY;INTERVAL=6 after completion · 90 min · Energy: high
			|
			|  Sections:
			|
			|  - hero
			|  - pricing
			|
			|  - [x] Hero text
			|  - [ ] Pricing table
			|
			|## Scheduled
			|
			|_No tasks._
			|
			""".trimMargin()
	}

	@Test
	fun `logbook md holds the done tasks newest first, without trashed ones`() {
		task(id(20), "File the return", "NEXT", completedAt = Instant.parse("2026-04-30T15:00:00Z"))
		task(
			id(21),
			"Water the plants",
			"SCHEDULED",
			scheduledDate = LocalDate.parse("2026-10-20"),
			recurrenceRule = "FREQ=WEEKLY;BYDAY=TU",
			recurrenceMode = "FIXED",
			completedAt = Instant.parse("2026-10-20T07:00:00Z"),
		)
		task(id(22), "Trashed done", "NEXT", completedAt = Instant.parse("2026-10-21T07:00:00Z"), deletedAt = T2)
		task(id(23), "Still open", "NEXT")

		export().readable("tasks/logbook.md") shouldBe
			"""
			|# Logbook
			|
			|- [x] Water the plants
			|  Scheduled: 2026-10-20 · Repeats: FREQ=WEEKLY;BYDAY=TU · Done: 2026-10-20
			|- [x] File the return
			|  Done: 2026-04-30
			|
			""".trimMargin()
	}

	// --- readable: projects/ ----------------------------------------------------------------------------------------

	@Test
	fun `a project mirror has its fields, description and tasks as checkboxes with the next step marked`() {
		val work = area(id(1), "Work")
		val launch =
			project(
				id(10),
				"Launch",
				area = work,
				description = "# Goal\n\nThe new site is **live**.\n\n",
				dueDate = LocalDate.parse("2026-12-01"),
				reviewDate = LocalDate.parse("2026-11-01"),
			)
		task(id(20), "Write copy", "NEXT", projectId = launch, position = 1)
		val draft = task(id(21), "Draft", "NEXT", projectId = launch, position = 0)
		task(id(22), "Kickoff", "NEXT", projectId = launch, completedAt = Instant.parse("2026-10-01T10:00:00Z"))
		task(id(23), "Old", "NEXT", projectId = launch, deletedAt = T2)
		task(id(24), "Mockups", "WAITING", projectId = launch, position = 2, waitingForText = "Anna")
		nextStep(launch, draft)

		export().readable("projects/Work/Launch.md") shouldBe
			"""
			|# Launch
			|
			|- Status: active
			|- Area: Work
			|- Due: 2026-12-01
			|- Review: 2026-11-01
			|
			|# Goal
			|
			|The new site is **live**.
			|
			|## Tasks
			|
			|- [ ] **Draft** (next step)
			|- [ ] Write copy
			|- [ ] Mockups
			|  Waiting for: Anna
			|- [x] Kickoff
			|  Done: 2026-10-01
			|
			""".trimMargin()
	}

	@Test
	fun `a completed project without tasks shows its status and completion date, a blank description is left out`() {
		project(id(10), "Tax return 2025", status = "COMPLETED", completedAt = Instant.parse("2026-04-30T16:00:00Z"))
		project(id(11), "Garden", status = "ON_HOLD", description = "  \n ")

		val zip = export()

		zip.readable("projects/No area/Tax return 2025.md") shouldBe
			"# Tax return 2025\n\n- Status: completed\n- Completed: 2026-04-30\n\n## Tasks\n\n_No tasks._\n"
		zip.readable("projects/No area/Garden.md") shouldBe "# Garden\n\n- Status: on hold\n\n## Tasks\n\n_No tasks._\n"
	}

	@Test
	fun `a due date without time and a waiting-for text outside the Waiting list are shown`() {
		task(id(20), "Pay the invoice", "NEXT", dueDate = LocalDate.parse("2026-11-30"), waitingForText = "Bookkeeper")

		export().readable("tasks/next.md") shouldStartWith
			"# Next\n\n- [ ] Pay the invoice\n  Due: 2026-11-30 · Waiting for: Bookkeeper\n"
	}

	@Test
	fun `projects without area go to No area, trashed projects and their tasks get no mirror`() {
		val work = area(id(1), "Work")
		project(id(10), "Launch", area = work)
		project(id(11), "Read more")
		val abandoned = project(id(12), "Abandoned plan", area = work, deletedAt = T2)
		task(id(20), "Leftover", "NEXT", projectId = abandoned)

		val zip = export()

		zip.projectPaths() shouldContainExactlyInAnyOrder listOf("projects/Work/Launch.md", "projects/No area/Read more.md")
		// The task itself is not in the Trash: it stays in its list, without the trashed project's name.
		zip.readable("tasks/next.md") shouldContain "- [ ] Leftover\n\n## Scheduled"
	}

	@Test
	fun `titles become file names without path separators, control characters, line breaks or leading dots`() {
		val slashed = area(id(1), "Home / Garden")
		project(id(10), "Launch website / v2", area = slashed)
		project(id(11), "back\\slash\ttab", area = slashed)
		project(id(12), ".hidden", area = slashed)
		project(id(13), "...", area = slashed)
		project(id(14), "two\nlines", area = slashed)
		project(id(15), "Zuhause «Garten» 🌱", area = slashed)

		export().projectPaths() shouldContainExactlyInAnyOrder
			listOf(
				"projects/Home Garden/Launch website v2.md",
				"projects/Home Garden/back slash tab.md",
				"projects/Home Garden/hidden.md",
				"projects/Home Garden/Untitled.md",
				"projects/Home Garden/two lines.md",
				"projects/Home Garden/Zuhause «Garten» 🌱.md",
			)
	}

	@Test
	fun `long titles are cut to 100 characters`() {
		project(id(10), "x".repeat(150))

		export().projectPaths() shouldBe listOf("projects/No area/${"x".repeat(100)}.md")
	}

	@Test
	fun `a long title cut inside an emoji still gives a valid, unique file name`() {
		// Character 100 is the first half of a surrogate pair: cutting there leaves half an emoji.
		project(id(10), "a".repeat(99) + "🌱 one")
		project(id(11), "a".repeat(99) + "😀 two")

		val paths = export().projectPaths()

		paths shouldHaveSize 2
		paths.toSet() shouldHaveSize 2
		paths.forEach { path -> withClue(path) { path.isValidUtf16() shouldBe true } }
	}

	@Test
	fun `project and area names that clash regardless of case are numbered, in id order`() {
		val work = area(id(1), "Work", position = 0)
		val work2 = area(id(2), "work", position = 1)
		val noArea = area(id(3), "No area", position = 2)
		project(id(10), "Plan", area = work)
		project(id(11), "plan", area = work)
		project(id(12), "Plan (2)", area = work)
		project(id(13), "Plan", area = work2)
		project(id(14), "Elsewhere", area = noArea)
		project(id(15), "Loose")

		export().projectPaths() shouldContainExactlyInAnyOrder
			listOf(
				"projects/Work/Plan.md",
				"projects/Work/plan (2).md",
				"projects/Work/Plan (2) (2).md",
				"projects/work (2)/Plan.md",
				"projects/No area/Elsewhere.md",
				"projects/No area (2)/Loose.md",
			)
	}

	// --- import -----------------------------------------------------------------------------------------------------

	@Test
	fun `import restores every row with its ids, times, versions, references and Trash state`() {
		val work = area(id(1), "Work", position = 2)
		val phone = context(id(2), "@phone", color = "#3366ff")
		val errands = context(id(3), "@errands")
		// The next step and the next occurrence are tasks with larger ids than the rows that point to them.
		val launch = project(id(10), "Launch", area = work, description = "# Goal", dueDate = LocalDate.parse("2026-12-01"))
		project(id(11), "Abandoned", status = "DROPPED", deletedAt = T2)
		val first =
			task(
				id(20),
				"Water the plants",
				"SCHEDULED",
				projectId = launch,
				contexts = listOf(phone, errands),
				scheduledDate = LocalDate.parse("2026-10-20"),
				recurrenceRule = "FREQ=WEEKLY;BYDAY=TU",
				recurrenceMode = "FIXED",
				completedAt = Instant.parse("2026-10-20T07:00:00.123456Z"),
				dueDate = LocalDate.parse("2026-10-20"),
				dueTime = LocalTime.parse("09:15"),
				waitingForContact = UUID.fromString("01890a5d-ac96-774b-bcce-b302099a8057"),
				version = 5,
			)
		val next = task(id(21), "Water the plants", "SCHEDULED", projectId = launch, scheduledDate = LocalDate.parse("2026-10-27"))
		jdbc.update("update task set next_occurrence_id = ? where id = ?", next, first)
		val draft = task(id(22), "Draft", "NEXT", projectId = launch, contexts = listOf(errands), notes = "multi\nline")
		task(id(23), "Old idea", "SOMEDAY", deletedAt = T2)
		nextStep(launch, draft)
		checklistItem(id(30), draft, "Hero text", done = true, position = 0)
		checklistItem(id(31), draft, "Pricing table", done = false, position = 1)
		val before = rows()

		val zip = export()
		wipe()
		import(zip.path)

		val after = rows()
		after.keys shouldBe before.keys
		before.forEach { (table, rows) -> withClue(table) { after[table] shouldBe rows } }
		jdbc.queryForObject("select next_task_id from project where id = ?", UUID::class.java, launch) shouldBe draft
		jdbc.queryForObject("select next_occurrence_id from task where id = ?", UUID::class.java, first) shouldBe next
	}

	@Test
	fun `import indexes tasks and projects for search, not the ones in the Trash`() {
		val launch = project(id(10), "Launch", description = "The new site")
		project(id(11), "Abandoned", deletedAt = T2)
		task(id(20), "Draft", "NEXT", projectId = launch, notes = "hero and pricing")
		task(id(21), "Filed", "NEXT", completedAt = T1)
		task(id(22), "Old idea", "SOMEDAY", deletedAt = T2)

		val zip = export()
		wipe()
		import(zip.path)

		jdbc.queryForList(
			"select item_type || ' ' || item_id || ' ' || title || ' | ' || body from search_document",
			String::class.java,
		) shouldContainExactlyInAnyOrder
			listOf(
				"PROJECT ${id(10)} Launch | The new site",
				"TASK ${id(20)} Draft | hero and pricing",
				"TASK ${id(21)} Filed | ",
			)
	}

	// --- helpers ----------------------------------------------------------------------------------------------------

	private class Exported(
		val path: Path,
		val counts: Map<String, Long>,
		private val json: tools.jackson.databind.json.JsonMapper,
	) {
		private val entries: Map<String, ByteArray> = Zips.read(path.readBytes())

		fun text(name: String) = String(entries.getValue(name), Charsets.UTF_8)

		fun json(name: String): JsonNode = json.readTree(text(name))

		fun lines(name: String): List<JsonNode> = text(name).lines().filter { it.isNotBlank() }.map { json.readTree(it) }

		fun tasks() = lines("data/tasks.jsonl")

		fun readable(path: String) = text(BackupFormat.READABLE_DIR + path)

		fun readablePaths() = entries.keys.filter { it.startsWith(BackupFormat.READABLE_DIR) }.map { it.removePrefix(BackupFormat.READABLE_DIR) }

		fun projectPaths() = readablePaths().filter { it.startsWith("projects/") }
	}

	private fun export(): Exported {
		val target = Files.createTempFile(work, "backup-", ".zip")
		val manifest = target.outputStream().use { backups.export(it) }
		return Exported(target, manifest.counts, backups.canonicalJson)
	}

	/** Runs only the GTD contributor's import, as restore does (one transaction, into an empty install). */
	private fun import(zip: Path) =
		ZipBackupReader.open(zip, backups.canonicalJson).use { reader ->
			transactions.executeWithoutResult { contributor.import(reader) }
		}

	private fun area(
		id: UUID,
		title: String,
		position: Int = 0,
	): UUID {
		jdbc.update(
			"insert into area (id, created_at, updated_at, version, title, position) values (?, ?, ?, ?, ?, ?)",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			2,
			title,
			position,
		)
		return id
	}

	private fun context(
		id: UUID,
		name: String,
		color: String? = null,
		position: Int = 0,
	): UUID {
		jdbc.update(
			"insert into context (id, created_at, updated_at, version, name, color, position) values (?, ?, ?, ?, ?, ?, ?)",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			1,
			name,
			color,
			position,
		)
		return id
	}

	private fun project(
		id: UUID,
		title: String,
		area: UUID? = null,
		status: String = "ACTIVE",
		description: String? = null,
		dueDate: LocalDate? = null,
		reviewDate: LocalDate? = null,
		completedAt: Instant? = null,
		deletedAt: Instant? = null,
	): UUID {
		jdbc.update(
			"""insert into project (id, created_at, updated_at, version, deleted_at, title, description_md, area_id, status,
			due_date, review_date, completed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			3,
			deletedAt?.let(Timestamp::from),
			title,
			description,
			area,
			status,
			dueDate,
			reviewDate,
			completedAt?.let(Timestamp::from),
		)
		return id
	}

	private fun nextStep(
		project: UUID,
		task: UUID,
	) = jdbc.update("update project set next_task_id = ? where id = ?", task, project)

	private fun task(
		id: UUID,
		title: String,
		list: String,
		projectId: UUID? = null,
		position: Int = 0,
		notes: String? = null,
		contexts: List<UUID> = emptyList(),
		energy: String? = null,
		creativity: String? = null,
		estimate: Int? = null,
		dueDate: LocalDate? = null,
		dueTime: LocalTime? = null,
		scheduledDate: LocalDate? = null,
		tickler: LocalDate? = null,
		waitingForContact: UUID? = null,
		waitingForText: String? = null,
		waitingSince: LocalDate? = null,
		recurrenceRule: String? = null,
		recurrenceMode: String? = null,
		nextOccurrence: UUID? = null,
		completedAt: Instant? = null,
		deletedAt: Instant? = null,
		version: Long = 1,
	): UUID {
		jdbc.update(
			"""insert into task (id, created_at, updated_at, version, deleted_at, title, notes_md, list, project_id, position,
			energy, creativity, time_estimate_min, due_date, due_time, scheduled_date, tickler_date, waiting_for_contact_id,
			waiting_for_text, waiting_since, recurrence_rule, recurrence_mode, next_occurrence_id, completed_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			version,
			deletedAt?.let(Timestamp::from),
			title,
			notes,
			list,
			projectId,
			position,
			energy,
			creativity,
			estimate,
			dueDate,
			dueTime,
			scheduledDate,
			tickler,
			waitingForContact,
			waitingForText,
			waitingSince,
			recurrenceRule,
			recurrenceMode,
			nextOccurrence,
			completedAt?.let(Timestamp::from),
		)
		contexts.forEach { jdbc.update("insert into task_context (task_id, context_id) values (?, ?)", id, it) }
		return id
	}

	private fun checklistItem(
		id: UUID,
		task: UUID,
		text: String,
		done: Boolean,
		position: Int,
	) {
		jdbc.update(
			"insert into checklist_item (id, created_at, updated_at, version, task_id, text, done, position) values (?, ?, ?, ?, ?, ?, ?, ?)",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			1,
			task,
			text,
			done,
			position,
		)
	}

	/** Every GTD table's rows, in a stable order. */
	private fun rows(): Map<String, List<Map<String, Any?>>> =
		mapOf(
			"area" to jdbc.queryForList("select * from area order by id"),
			"context" to jdbc.queryForList("select * from context order by id"),
			"project" to jdbc.queryForList("select * from project order by id"),
			"task" to jdbc.queryForList("select * from task order by id"),
			"task_context" to jdbc.queryForList("select * from task_context order by task_id, context_id"),
			"checklist_item" to jdbc.queryForList("select * from checklist_item order by id"),
		)

	private fun count(table: String) = jdbc.queryForObject("select count(*) from $table", Long::class.java)

	private fun JsonNode.isNullOrAbsent(property: String) = get(property)?.isNull ?: true

	/** No unpaired surrogate, i.e. the string can be encoded as UTF-8 without loss. */
	private fun String.isValidUtf16(): Boolean = Charsets.UTF_8.newEncoder().canEncode(this)

	companion object {
		private const val DATABASE = "gtd_backup_contributor"
		private val dataDir: Path = Files.createTempDirectory("cockpit-gtd-backup-data")

		private val T0 = Instant.parse("2026-03-01T08:15:30.123456Z")
		private val T1 = Instant.parse("2026-03-02T09:00:00Z")
		private val T2 = Instant.parse("2026-03-03T10:00:00Z")

		/** Fictitious UUIDv7-shaped ids, ordered by [n]. */
		private fun id(n: Int): UUID = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, DATABASE, dataDir)
	}
}
