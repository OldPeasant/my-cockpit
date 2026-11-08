package ch.sonensei.cockpit.mail.read

import android.net.Uri
import android.webkit.WebResourceRequest
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import ch.sonensei.cockpit.mail.list.MailRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/** A settings repository whose current settings are [current] (`null`: not loaded yet). */
internal fun settingsWith(current: UserSettings? = null): UserSettingsRepository {
    val flow = MutableStateFlow(current)
    return mockk { every { settings } returns flow }
}

/** A view model for tests: default settings (images blocked) and an image fetcher that is never asked. */
internal fun readViewModel(
    reader: MailReadRepository,
    mail: MailRepository,
    settings: UserSettingsRepository = settingsWith(),
): MailReadViewModel = MailReadViewModel(reader, mail, settings, mockk())

/** A resource request of the mail page, as the WebView hands it to `shouldInterceptRequest` (needs Robolectric). */
internal fun pageRequest(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
) = object : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)

    override fun isForMainFrame() = false

    override fun isRedirect() = false

    override fun hasGesture() = false

    override fun getMethod() = method

    override fun getRequestHeaders(): Map<String, String> = headers
}
