package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.auth.infra.CockpitAuthentication
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithSecurityContext
import org.springframework.security.test.context.support.WithSecurityContextFactory
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

/**
 * Runs a test (class or method) as the logged-in owner, the same authentication a session cookie produces, without
 * an account in the database. Use it in any module's MockMvc test to call authenticated API endpoints;
 * state-changing requests still need `with(csrf())`.
 *
 * ```
 * @SpringBootTest @AutoConfigureMockMvc @WithCockpitUser
 * class TaskWebTests(...) : PostgresTestBase() { ... }
 * ```
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@WithSecurityContext(factory = WithCockpitUserSecurityContextFactory::class)
annotation class WithCockpitUser(
	val username: String = TestCockpitUser.USERNAME,
	val userId: String = TestCockpitUser.USER_ID,
)

/** The fictitious owner used by [WithCockpitUser] and [cockpitUser]. */
object TestCockpitUser {
	const val USERNAME = "jane.doe"
	const val USER_ID = "0192a4c0-0000-7000-8000-00000000c0de"

	val principal = CockpitPrincipal(UUID.fromString(USER_ID), USERNAME)
}

/** The same as [WithCockpitUser] for a single MockMvc request: `mockMvc.get(url) { with(cockpitUser()) }`. */
fun cockpitUser(principal: CockpitPrincipal = TestCockpitUser.principal): RequestPostProcessor =
	authentication(CockpitAuthentication(principal))

class WithCockpitUserSecurityContextFactory : WithSecurityContextFactory<WithCockpitUser> {
	override fun createSecurityContext(annotation: WithCockpitUser): SecurityContext =
		SecurityContextHolder.createEmptyContext().apply {
			authentication = CockpitAuthentication(CockpitPrincipal(UUID.fromString(annotation.userId), annotation.username))
		}
}
