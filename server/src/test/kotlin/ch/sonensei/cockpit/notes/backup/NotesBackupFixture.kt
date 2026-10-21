package ch.sonensei.cockpit.notes.backup

import ch.sonensei.cockpit.backup.BackupFixture
import ch.sonensei.cockpit.notes.domain.FieldChange
import ch.sonensei.cockpit.notes.domain.NoteChanges
import ch.sonensei.cockpit.notes.domain.NoteService

/**
 * Notes with images, tags (unicode, differing spellings), inline links, revisions (also from a restore), an untitled
 * empty note, a long note, pinned and archived notes and one in the Trash with its revision.
 */
class NotesBackupFixture(
	private val notes: NoteService,
) : BackupFixture {
	override val contributor = NotesBackupContributor.ID

	override fun seed() {
		val trip =
			notes.create(
				NoteChanges(
					title = FieldChange("Trip to Zürich «2026»"),
					bodyMd = FieldChange("# Packing\n\n- [ ] passport\n"),
					tags = FieldChange(listOf("Travel", "café ☕")),
					pinned = FieldChange(true),
				),
			)
		notes.update(trip.id, NoteChanges(bodyMd = FieldChange("# Packing\n\n- [x] passport\n"))) {}
		notes.restoreRevision(trip.id, 1) {}
		// The newest revision is recent now, so this change keeps none: the note ends with its image.
		val image = notes.addImage(trip.id, "map of the old town.png", "image/png", PNG.inputStream())
		notes.update(trip.id, NoteChanges(bodyMd = FieldChange("# Packing\n\n![Map](cockpit-file:${image.id})\n- [x] passport\n"))) {}

		notes.create(
			NoteChanges(
				bodyMd = FieldChange("Ideas, see [[note:${trip.id}]]\n\n" + "A long line of thoughts. ".repeat(2_000)),
				tags = FieldChange(listOf("travel", "ideas")),
				archived = FieldChange(true),
			),
		)
		notes.create(NoteChanges())

		val draft = notes.create(NoteChanges(title = FieldChange("Draft"), bodyMd = FieldChange("first")))
		notes.update(draft.id, NoteChanges(bodyMd = FieldChange("second"))) {}
		notes.delete(draft.id) {}
	}

	companion object {
		/** The smallest PNG header; the content is never decoded. */
		private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
	}
}
