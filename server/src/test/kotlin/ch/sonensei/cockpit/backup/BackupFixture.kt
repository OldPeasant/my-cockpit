package ch.sonensei.cockpit.backup

/**
 * Seeds one module's data for [BackupRoundTripTest] (SPEC.md §12): every entity type the module's
 * `BackupContributor` exports, with the edge cases worth a round trip (soft-deleted rows, empty and long texts,
 * nested folders, ...).
 *
 * Write it as a plain class (no `@Component`) under `ch.sonensei.cockpit`, in the module's test package; the
 * round-trip test finds every implementation on the test classpath and creates it with constructor injection of
 * Spring beans. [seed] runs in a transaction, against an empty database and data directory (`cockpit.data-dir`).
 */
interface BackupFixture {
	/** Id of the `BackupContributor` whose data this fixture seeds; every contributor needs a fixture. */
	val contributor: String

	/** Creates the data. Every data name the contributor exports must end up with at least one record. */
	fun seed()
}
