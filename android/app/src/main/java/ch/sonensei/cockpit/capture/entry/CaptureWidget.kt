package ch.sonensei.cockpit.capture.entry

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import ch.sonensei.cockpit.ui.theme.CockpitDarkColors
import ch.sonensei.cockpit.ui.theme.CockpitLightColors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

object CaptureWidgetTags {
    const val PILL = "capture-widget:pill"
    const val PENDING = "capture-widget:pending"
}

/**
 * Home-screen capture widget (SPEC.md §4.5): one pill that looks like the capture sheet's title field and opens it,
 * plus how many captures still wait to reach the server. Widgets cannot host a real text field, so the typing happens
 * in [CaptureActivity]; the count follows the queue while the widget is shown and [CaptureWidgetUpdater] refreshes it
 * otherwise.
 */
class CaptureWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val queue = EntryPointAccessors.fromApplication(context, CaptureEntryPoint::class.java).captureQueue()
        val initial = queue.status.first()
        provideContent {
            val status by queue.status.collectAsState(initial)
            GlanceTheme(colors = widgetColors()) { CaptureWidgetContent(status) }
        }
    }
}

class CaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CaptureWidget()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptureEntryPoint {
    fun captureQueue(): CaptureQueue
}

/** The wallpaper colours from Android 12 on, like the app; the cockpit palette before. */
private fun widgetColors() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        DynamicThemeColorProviders
    } else {
        ColorProviders(light = CockpitLightColors, dark = CockpitDarkColors)
    }

/** The count badge text, or null when nothing waits: an all-synced widget stays quiet. */
fun pendingLabel(
    resources: Resources,
    status: CaptureQueueStatus,
): String? =
    when {
        status.pendingCount <= 0 -> null
        status.lastError != null -> resources.getQuantityString(R.plurals.capture_pending_failed, status.pendingCount, status.pendingCount)
        else -> resources.getQuantityString(R.plurals.capture_pending, status.pendingCount, status.pendingCount)
    }

@Composable
fun CaptureWidgetContent(status: CaptureQueueStatus) {
    val context = LocalContext.current
    val pending = pendingLabel(context.resources, status)
    val open = context.getString(R.string.capture_widget_open)
    Row(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(28.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .clickable(actionStartActivity(Intent(context, CaptureActivity::class.java)))
                .semantics {
                    contentDescription = listOfNotNull(open, pending).joinToString(", ")
                    testTag = CaptureWidgetTags.PILL
                }.padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_capture_inbox),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(20.dp),
        )
        Spacer(GlanceModifier.width(12.dp))
        Text(
            text = context.getString(R.string.share_text_hint),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (pending != null) {
            val failed = status.lastError != null
            Box(
                modifier =
                    GlanceModifier
                        .cornerRadius(12.dp)
                        .background(if (failed) GlanceTheme.colors.errorContainer else GlanceTheme.colors.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .semantics { testTag = CaptureWidgetTags.PENDING },
            ) {
                Text(
                    text = pending,
                    style =
                        TextStyle(
                            color = if (failed) GlanceTheme.colors.onErrorContainer else GlanceTheme.colors.onSecondaryContainer,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    maxLines = 1,
                )
            }
        }
    }
}
