package ch.sonensei.cockpit.core.settings.infra

import ch.sonensei.cockpit.core.settings.domain.UserSettings
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserSettingsRepository : JpaRepository<UserSettings, UUID> {
	/** The single settings row, if it was created yet. */
	fun findFirstBy(): UserSettings?
}
