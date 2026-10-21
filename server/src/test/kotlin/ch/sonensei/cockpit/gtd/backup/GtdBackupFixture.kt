package ch.sonensei.cockpit.gtd.backup

import ch.sonensei.cockpit.backup.BackupFixture
import ch.sonensei.cockpit.gtd.domain.Area
import ch.sonensei.cockpit.gtd.domain.ChecklistItem
import ch.sonensei.cockpit.gtd.domain.Context
import ch.sonensei.cockpit.gtd.domain.Creativity
import ch.sonensei.cockpit.gtd.domain.Energy
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.domain.RecurrenceMode
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.infra.AreaRepository
import ch.sonensei.cockpit.gtd.infra.ChecklistItemRepository
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.gtd.infra.TaskRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Tasks on every list (Inbox, Next, Waiting For with a contact and a text, Someday, Scheduled, a tickler, done ones and
 * one in the Trash), a recurring task done with its next occurrence and a recurring one after completion, projects
 * with next steps (one in an area, one without, a completed and a trashed one), areas, contexts, a task with several
 * contexts and a checklist, unicode, empty and multi-line texts.
 */
class GtdBackupFixture(
	private val areas: AreaRepository,
	private val contexts: ContextRepository,
	private val projects: ProjectRepository,
	private val tasks: TaskRepository,
	private val checklists: ChecklistItemRepository,
	private val clock: Clock,
) : BackupFixture {
	override val contributor = GtdBackupContributor.ID

	override fun seed() {
		val work = areas.saveAndFlush(Area("Work", 0))
		val home = areas.saveAndFlush(Area("Zuhause «Garten» 🌱", 1))
		val phone = contexts.saveAndFlush(Context("@phone", "#3366ff", 0))
		val errands = contexts.saveAndFlush(Context("@errands", null, 1))

		val launch =
			projects.saveAndFlush(
				Project("Launch website / v2", ProjectStatus.ACTIVE).apply {
					areaId = work.id
					descriptionMd = "# Goal\n\nThe new site is **live**.\n"
					dueDate = LocalDate.parse("2026-12-01")
					reviewDate = LocalDate.parse("2026-11-01")
				},
			)
		val garden = projects.saveAndFlush(Project("Garten winterfest machen", ProjectStatus.ON_HOLD).apply { areaId = home.id })
		val loose = projects.saveAndFlush(Project("Read more", ProjectStatus.SOMEDAY))
		val finished =
			projects.saveAndFlush(
				Project("Tax return 2025", ProjectStatus.COMPLETED).apply { completedAt = Instant.parse("2026-04-30T16:00:00Z") },
			)
		projects.saveAndFlush(Project("Abandoned plan", ProjectStatus.DROPPED).apply { softDelete(clock) })

		task("Call the bank", TaskList.INBOX)
		task("", TaskList.INBOX) { notesMd = "" }
		val draft =
			task("Draft the landing page", TaskList.NEXT) {
				projectId = launch.id
				notesMd = "Sections:\n\n- hero\n- pricing"
				contextIds = mutableSetOf(phone.id, errands.id)
				energy = Energy.HIGH
				creativity = Creativity.CREATIVE
				timeEstimateMin = 90
				dueDate = LocalDate.parse("2026-11-15")
				dueTime = LocalTime.parse("14:30")
			}
		checklists.saveAndFlush(ChecklistItem(draft.id, "Hero text", true, 0))
		checklists.saveAndFlush(ChecklistItem(draft.id, "Pricing table", false, 1))
		task("Cover the beds", TaskList.NEXT) { projectId = garden.id }
		task("Wait for the designer's mockups", TaskList.WAITING) {
			projectId = launch.id
			waitingForContactId = UUID.fromString("01890a5d-ac96-774b-bcce-b302099a8057")
			waitingForText = "Anna (design)"
			waitingSince = LocalDate.parse("2026-10-10")
		}
		task("Learn Esperanto", TaskList.SOMEDAY) { projectId = loose.id }
		task("Renew passport", TaskList.SCHEDULED) { scheduledDate = LocalDate.parse("2026-11-20") }
		task("Check the offer again", TaskList.INBOX) { ticklerDate = LocalDate.parse("2027-01-10") }
		task("File the return", TaskList.NEXT) {
			projectId = finished.id
			completedAt = Instant.parse("2026-04-30T15:00:00Z")
		}
		task("Old idea", TaskList.SOMEDAY) { softDelete(clock) }

		val nextWeek =
			task("Water the plants", TaskList.SCHEDULED) {
				scheduledDate = LocalDate.parse("2026-10-27")
				recurrenceRule = "FREQ=WEEKLY;BYDAY=TU"
				recurrenceMode = RecurrenceMode.FIXED
				contextIds = mutableSetOf(errands.id)
			}
		task("Water the plants", TaskList.SCHEDULED) {
			scheduledDate = LocalDate.parse("2026-10-20")
			recurrenceRule = "FREQ=WEEKLY;BYDAY=TU"
			recurrenceMode = RecurrenceMode.FIXED
			nextOccurrenceId = nextWeek.id
			completedAt = Instant.parse("2026-10-20T07:00:00.123456Z")
		}
		task("Get a haircut", TaskList.NEXT) {
			recurrenceRule = "FREQ=WEEKLY;INTERVAL=6"
			recurrenceMode = RecurrenceMode.AFTER_COMPLETION
		}

		launch.nextTaskId = draft.id
		projects.saveAndFlush(launch)
		garden.nextTaskId = tasks.findByProjectIdOrderByPositionAscIdAsc(garden.id).single().id
		projects.saveAndFlush(garden)
	}

	private fun task(
		title: String,
		list: TaskList,
		configure: Task.() -> Unit = {},
	): Task = tasks.saveAndFlush(Task(title, list).apply(configure))
}
