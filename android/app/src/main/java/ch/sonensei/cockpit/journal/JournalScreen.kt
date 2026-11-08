package ch.sonensei.cockpit.journal

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.notes.MarkdownView
import ch.sonensei.cockpit.notes.NotePhotoFiles
import ch.sonensei.cockpit.notes.PhotoSource
import ch.sonensei.cockpit.notes.SaveStatus
import ch.sonensei.cockpit.ui.PlaceholderScreen
import coil3.ImageLoader
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/** What the journal can ask for, so it can be tested without a view model. */
data class JournalCallbacks(
    val setEditing: (Boolean) -> Unit = {},
    val setBody: (String) -> Unit = {},
    /** The source, and where in the body the image goes (the cursor). */
    val addPhoto: (PhotoSource, Int) -> Unit = { _, _ -> },
    val saveNow: () -> Unit = {},
    val keepMine: () -> Unit = {},
    val useTheirs: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
    val retryLoad: () -> Unit = {},
    val previousDay: () -> Unit = {},
    val nextDay: () -> Unit = {},
    val openToday: () -> Unit = {},
    val openDay: (LocalDate) -> Unit = {},
    val showMonth: (YearMonth) -> Unit = {},
    val setMood: (Int) -> Unit = {},
    val setTags: (String) -> Unit = {},
    val tidyTags: () -> Unit = {},
)

internal object JournalTags {
    const val DATE = "journal-date"
    const val RELATIVE = "journal-relative"
    const val STATUS = "journal-status"
    const val SAVE_RETRY = "journal-save-retry"
    const val TOGGLE = "journal-toggle"
    const val BODY = "journal-body"
    const val VIEW = "journal-view"
    const val LOADING = "journal-loading"
    const val CAMERA = "journal-camera"
    const val GALLERY = "journal-gallery"
    const val CONFLICT = "journal-conflict"
    const val KEEP_MINE = "journal-keep-mine"
    const val USE_THEIRS = "journal-use-theirs"
    const val MESSAGE = "journal-message"
    const val UPLOADING = "journal-uploading"
    const val RETRY_LOAD = "journal-retry-load"
    const val PREVIOUS_DAY = "journal-previous-day"
    const val NEXT_DAY = "journal-next-day"
    const val TODAY = "journal-today"
    const val PICK_DATE = "journal-pick-date"
    const val DATE_PICKER = "journal-date-picker"
    const val CALENDAR_TOGGLE = "journal-calendar-toggle"
    const val CALENDAR = "journal-calendar"
    const val MONTH_TITLE = "journal-month-title"
    const val PREVIOUS_MONTH = "journal-previous-month"
    const val NEXT_MONTH = "journal-next-month"
    const val CALENDAR_RETRY = "journal-calendar-retry"
    const val TAGS = "journal-tags"
    const val ON_THIS_DAY = "journal-on-this-day"
    const val ON_THIS_DAY_LOADING = "journal-on-this-day-loading"

    fun day(date: LocalDate) = "journal-day-$date"

    fun mood(mood: Int) = "journal-mood-$mood"

    fun earlier(date: LocalDate) = "journal-earlier-$date"
}

/**
 * The Journal screen: today's entry first. Its view model comes from Hilt, so outside a Hilt activity (the shell's
 * navigation tests, previews) it shows the placeholder instead of failing.
 */
@Composable
fun JournalScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        JournalRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_journal, Icons.AutoMirrored.Outlined.MenuBook, modifier)
    }
}

/** A day's entry, with photos from the camera or the gallery. */
@Composable
fun JournalRoute(
    modifier: Modifier = Modifier,
    viewModel: JournalViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var insertAt by rememberSaveable { mutableStateOf(0) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    // The day the camera or gallery was opened for (ISO date). Saved like cameraUri: after process death the view
    // model starts again on today, and the photo must not land there.
    var photoDay by rememberSaveable { mutableStateOf<String?>(null) }

    fun upload(uri: Uri) {
        // Captured now, not after reading the file: that takes a moment, and another day may open meanwhile.
        val date = photoDay?.let(LocalDate::parse) ?: viewModel.uiState.value.date
        photoDay = null
        scope.launch { viewModel.addPhoto(NotePhotoFiles.read(context, uri), insertAt, date) }
    }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val uri = cameraUri?.let(Uri::parse)
            cameraUri = null
            if (taken && uri != null) upload(uri)
        }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) upload(uri)
        }

    JournalContent(
        state = state,
        imageLoader = viewModel.imageLoader,
        callbacks =
            JournalCallbacks(
                setEditing = viewModel::setEditing,
                setBody = viewModel::setBody,
                addPhoto = { source, at ->
                    insertAt = at
                    photoDay =
                        viewModel.uiState.value.date
                            .toString()
                    when (source) {
                        PhotoSource.CAMERA -> {
                            NotePhotoFiles.clear(context)
                            val (_, uri) = NotePhotoFiles.create(context)
                            cameraUri = uri.toString()
                            try {
                                camera.launch(uri)
                            } catch (_: ActivityNotFoundException) {
                                cameraUri = null
                                viewModel.addPhoto(null)
                            }
                        }
                        PhotoSource.GALLERY ->
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                },
                saveNow = { viewModel.saveNow() },
                keepMine = viewModel::keepMine,
                useTheirs = viewModel::useTheirs,
                dismissMessage = viewModel::dismissMessage,
                retryLoad = viewModel::retryLoad,
                previousDay = viewModel::previousDay,
                nextDay = viewModel::nextDay,
                openToday = viewModel::openToday,
                openDay = viewModel::openDay,
                showMonth = viewModel::showMonth,
                setMood = viewModel::setMood,
                setTags = viewModel::setTags,
                tidyTags = viewModel::tidyTags,
            ),
        modifier = modifier,
    )
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy")

@Composable
fun JournalContent(
    state: JournalUiState,
    callbacks: JournalCallbacks,
    imageLoader: ImageLoader?,
    modifier: Modifier = Modifier,
) {
    // The body with its cursor; follows the state when the text changes from elsewhere (load, photo, conflict).
    var body by remember { mutableStateOf(TextFieldValue(state.body)) }
    if (body.text != state.body) {
        val cursor = (body.selection.start + state.body.length - body.text.length).coerceIn(0, state.body.length)
        body = TextFieldValue(state.body, TextRange(cursor))
    }
    var calendarShown by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        DayBar(
            state,
            callbacks,
            calendarShown = calendarShown,
            toggleCalendar = { calendarShown = !calendarShown },
            pickDate = { picking = true },
        )
        JournalBar(state, callbacks, insertAt = body.selection.start)
        state.conflict?.let { ConflictBanner(it, callbacks) }
        state.message?.let { MessageBanner(it, callbacks) }
        if (state.uploading || state.loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().testTag(if (state.uploading) JournalTags.UPLOADING else JournalTags.LOADING),
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DayHeading(state)
                if (calendarShown) MonthCalendar(state, callbacks)
                if (!state.loading && !state.unloaded) {
                    MoodRow(state, callbacks)
                    if (state.editing) {
                        OutlinedTextField(
                            value = body,
                            onValueChange = {
                                body = it
                                if (it.text != state.body) callbacks.setBody(it.text)
                            },
                            placeholder = { Text(stringResource(R.string.journal_body_hint)) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            enabled = state.editable,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag(JournalTags.BODY),
                        )
                    } else {
                        Column(Modifier.fillMaxWidth().testTag(JournalTags.VIEW)) {
                            if (state.body.isBlank()) {
                                Text(
                                    stringResource(R.string.journal_empty_body),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                MarkdownView(state.body, imageLoader)
                            }
                        }
                    }
                    TagsField(state, callbacks)
                }
                if (!state.loading) OnThisDay(state, callbacks)
            }
        }
    }
    if (picking) DatePick(state, onPick = callbacks.openDay, close = { picking = false })
}

/** Previous and next day, the date picker, Today and the month calendar. */
@Composable
private fun DayBar(
    state: JournalUiState,
    callbacks: JournalCallbacks,
    calendarShown: Boolean,
    toggleCalendar: () -> Unit,
    pickDate: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = callbacks.previousDay, modifier = Modifier.testTag(JournalTags.PREVIOUS_DAY)) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = stringResource(R.string.journal_previous_day))
        }
        IconButton(
            onClick = callbacks.nextDay,
            enabled = state.canGoForward,
            modifier = Modifier.testTag(JournalTags.NEXT_DAY),
        ) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = stringResource(R.string.journal_next_day))
        }
        IconButton(onClick = pickDate, modifier = Modifier.testTag(JournalTags.PICK_DATE)) {
            Icon(Icons.Outlined.Event, contentDescription = stringResource(R.string.journal_pick_date))
        }
        Box(Modifier.weight(1f))
        if (!state.isToday) {
            TextButton(onClick = callbacks.openToday, modifier = Modifier.testTag(JournalTags.TODAY)) {
                Text(stringResource(R.string.journal_today))
            }
        }
        IconButton(
            onClick = toggleCalendar,
            modifier = Modifier.testTag(JournalTags.CALENDAR_TOGGLE).semantics { selected = calendarShown },
        ) {
            Icon(
                Icons.Outlined.CalendarMonth,
                contentDescription =
                    stringResource(if (calendarShown) R.string.journal_calendar_hide else R.string.journal_calendar_show),
            )
        }
    }
}

@Composable
private fun DayHeading(state: JournalUiState) {
    Column {
        val relative =
            when (state.date) {
                state.today -> R.string.journal_today
                state.today.minusDays(1) -> R.string.journal_yesterday
                else -> null
            }
        if (relative != null) {
            Text(
                stringResource(relative),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(JournalTags.RELATIVE),
            )
        }
        Text(
            state.date.format(dateFormat.withLocale(LocalLocale.current.platformLocale)),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }.testTag(JournalTags.DATE),
        )
    }
}

/** The system date picker; days after today can't be chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePick(
    state: JournalUiState,
    onPick: (LocalDate) -> Unit,
    close: () -> Unit,
) {
    val today = state.today
    // The picker works in UTC midnights.
    val pickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = state.date.toUtcMillis(),
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis.toUtcDate() <= today

                    override fun isSelectableYear(year: Int) = year <= today.year
                },
        )
    DatePickerDialog(
        onDismissRequest = close,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { onPick(it.toUtcDate()) }
                close()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.journal_cancel)) } },
        modifier = Modifier.testTag(JournalTags.DATE_PICKER),
    ) { DatePicker(pickerState) }
}

private fun LocalDate.toUtcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** How strongly a day with an entry is marked: by its mood, an entry without one in the middle (as on the web). */
private fun moodAlpha(mood: Int?): Float =
    when (mood) {
        1 -> 0.25f
        2 -> 0.4f
        4 -> 0.8f
        5 -> 1f
        else -> 0.6f
    }

/** The month calendar: days with an entry are marked, the shown day is outlined; a tap opens a day. */
@Composable
private fun MonthCalendar(
    state: JournalUiState,
    callbacks: JournalCallbacks,
) {
    val locale = LocalLocale.current.platformLocale
    val firstDay: DayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val weeks = remember(state.month, firstDay) { JournalText.monthWeeks(state.month, firstDay) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag(JournalTags.CALENDAR),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { callbacks.showMonth(state.month.minusMonths(1)) },
                    modifier = Modifier.testTag(JournalTags.PREVIOUS_MONTH),
                ) { Icon(Icons.Outlined.ChevronLeft, contentDescription = stringResource(R.string.journal_previous_month)) }
                Text(
                    state.month.format(monthFormat.withLocale(locale)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).testTag(JournalTags.MONTH_TITLE),
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = { callbacks.showMonth(state.month.plusMonths(1)) },
                    enabled = state.canShowNextMonth,
                    modifier = Modifier.testTag(JournalTags.NEXT_MONTH),
                ) { Icon(Icons.Outlined.ChevronRight, contentDescription = stringResource(R.string.journal_next_month)) }
            }
            if (state.monthLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                (0 until DAYS_IN_WEEK).forEach { offset ->
                    Text(
                        firstDay.plus(offset.toLong()).getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    )
                }
            }
            weeks.forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                            if (date != null) CalendarDay(date, state, callbacks)
                        }
                    }
                }
            }
            if (state.monthFailed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.journal_calendar_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    TextButton(
                        onClick = { callbacks.showMonth(state.month) },
                        modifier = Modifier.testTag(JournalTags.CALENDAR_RETRY),
                    ) { Text(stringResource(R.string.journal_retry)) }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    state: JournalUiState,
    callbacks: JournalCallbacks,
) {
    val hasEntry = date in state.monthDays
    val future = date > state.today
    val shown = date == state.date
    val colors = MaterialTheme.colorScheme
    val label =
        date.format(dateFormat.withLocale(LocalLocale.current.platformLocale)).let {
            if (hasEntry) stringResource(R.string.journal_day_with_entry, it) else it
        }
    val alpha = moodAlpha(state.monthDays[date])
    var cell =
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
    if (hasEntry) cell = cell.background(colors.primary.copy(alpha = alpha))
    if (shown) cell = cell.border(2.dp, colors.onSurface, CircleShape)
    Box(
        cell
            .clickable(enabled = !future) { callbacks.openDay(date) }
            .semantics {
                contentDescription = label
                selected = shown
            }.testTag(JournalTags.day(date)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (date == state.today) FontWeight.Bold else FontWeight.Normal,
            color =
                when {
                    future -> colors.onSurface.copy(alpha = 0.38f)
                    hasEntry && alpha >= 0.6f -> colors.onPrimary
                    else -> colors.onSurface
                },
        )
    }
}

private const val DAYS_IN_WEEK = 7

@Composable
private fun moodLabel(mood: Int): String =
    stringResource(
        when (mood) {
            1 -> R.string.journal_mood_1
            2 -> R.string.journal_mood_2
            3 -> R.string.journal_mood_3
            4 -> R.string.journal_mood_4
            else -> R.string.journal_mood_5
        },
    )

/** The mood of the day, 1 (bad) to 5 (great); the chosen one again clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodRow(
    state: JournalUiState,
    callbacks: JournalCallbacks,
) {
    val title = stringResource(R.string.journal_mood)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.semantics { contentDescription = title },
        ) {
            JournalViewModel.MOODS.forEach { mood ->
                FilterChip(
                    selected = state.mood == mood,
                    onClick = { callbacks.setMood(mood) },
                    enabled = state.editable,
                    label = { Text(moodLabel(mood)) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = moodAlpha(mood))),
                        )
                    },
                    modifier = Modifier.testTag(JournalTags.mood(mood)),
                )
            }
        }
    }
}

@Composable
private fun TagsField(
    state: JournalUiState,
    callbacks: JournalCallbacks,
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = state.tagsText,
        onValueChange = callbacks.setTags,
        label = { Text(stringResource(R.string.journal_tags)) },
        placeholder = { Text(stringResource(R.string.journal_tags_hint)) },
        singleLine = true,
        enabled = state.editable,
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (focused && !it.isFocused) callbacks.tidyTags()
                    focused = it.isFocused
                }.testTag(JournalTags.TAGS),
    )
}

/** The entries of this day in earlier years; a tap opens one. */
@Composable
private fun OnThisDay(
    state: JournalUiState,
    callbacks: JournalCallbacks,
) {
    Column(Modifier.fillMaxWidth().testTag(JournalTags.ON_THIS_DAY), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(
            stringResource(R.string.journal_on_this_day),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when {
            state.onThisDayLoading -> {
                val loading = stringResource(R.string.journal_on_this_day_loading)
                CircularProgressIndicator(
                    Modifier
                        .size(24.dp)
                        .semantics { contentDescription = loading }
                        .testTag(JournalTags.ON_THIS_DAY_LOADING),
                )
            }
            state.onThisDayFailed ->
                Text(stringResource(R.string.journal_on_this_day_failed), style = MaterialTheme.typography.bodyMedium, color = muted)
            state.onThisDay.isEmpty() ->
                Text(stringResource(R.string.journal_on_this_day_none), style = MaterialTheme.typography.bodyMedium, color = muted)
            else ->
                state.onThisDay.forEach { earlier ->
                    val years = state.date.year - earlier.date.year
                    val text = JournalText.excerpt(earlier.bodyMd).ifEmpty { earlier.mood?.let { moodLabel(it) } ?: "" }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { callbacks.openDay(earlier.date) }
                            .padding(vertical = 6.dp)
                            .testTag(JournalTags.earlier(earlier.date)),
                    ) {
                        Text(
                            pluralStringResource(R.plurals.journal_years_ago, years, earlier.date.year.toString(), years),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (text.isNotEmpty()) {
                            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        }
                    }
                }
        }
    }
}

@Composable
private fun JournalBar(
    state: JournalUiState,
    callbacks: JournalCallbacks,
    insertAt: Int,
) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (!state.loading && !state.unloaded) {
                val text =
                    when (state.status) {
                        SaveStatus.SAVED -> R.string.journal_status_saved
                        SaveStatus.UNSAVED -> R.string.journal_status_unsaved
                        SaveStatus.SAVING -> R.string.journal_status_saving
                        SaveStatus.OFFLINE -> R.string.journal_status_offline
                        SaveStatus.FAILED -> R.string.journal_status_failed
                    }
                val failed = state.status == SaveStatus.OFFLINE || state.status == SaveStatus.FAILED
                Text(
                    stringResource(text),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false).testTag(JournalTags.STATUS),
                )
                if (failed && state.conflict == null) {
                    TextButton(onClick = callbacks.saveNow, modifier = Modifier.testTag(JournalTags.SAVE_RETRY)) {
                        Text(stringResource(R.string.journal_save_retry))
                    }
                }
            }
        }
        if (state.editable) {
            if (state.uploading) {
                val uploading = stringResource(R.string.journal_uploading)
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp).semantics { contentDescription = uploading })
            } else {
                IconButton(
                    onClick = { callbacks.addPhoto(PhotoSource.CAMERA, insertAt) },
                    modifier = Modifier.testTag(JournalTags.CAMERA),
                ) {
                    Icon(Icons.Outlined.AddAPhoto, contentDescription = stringResource(R.string.journal_take_photo))
                }
                IconButton(
                    onClick = { callbacks.addPhoto(PhotoSource.GALLERY, insertAt) },
                    modifier = Modifier.testTag(JournalTags.GALLERY),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = stringResource(R.string.journal_pick_photo))
                }
            }
            TextButton(onClick = { callbacks.setEditing(!state.editing) }, modifier = Modifier.testTag(JournalTags.TOGGLE)) {
                Text(stringResource(if (state.editing) R.string.journal_view else R.string.journal_edit))
            }
        }
    }
}

@Composable
private fun ConflictBanner(
    conflict: JournalConflict,
    callbacks: JournalCallbacks,
) {
    val deleted = conflict.current == null
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(JournalTags.CONFLICT)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(if (deleted) R.string.journal_conflict_deleted else R.string.journal_conflict),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = callbacks.keepMine, modifier = Modifier.testTag(JournalTags.KEEP_MINE)) {
                    Text(stringResource(if (deleted) R.string.journal_keep_mine_deleted else R.string.journal_keep_mine))
                }
                OutlinedButton(onClick = callbacks.useTheirs, modifier = Modifier.testTag(JournalTags.USE_THEIRS)) {
                    Text(stringResource(if (deleted) R.string.journal_use_theirs_deleted else R.string.journal_use_theirs))
                }
            }
        }
    }
}

@Composable
private fun MessageBanner(
    message: JournalMessage,
    callbacks: JournalCallbacks,
) {
    val text =
        when (message) {
            JournalMessage.LOAD_OFFLINE -> R.string.journal_load_offline
            JournalMessage.LOAD_FAILED -> R.string.journal_load_failed
            JournalMessage.PHOTO_UNREADABLE -> R.string.journal_photo_unreadable
            JournalMessage.PHOTO_OFFLINE -> R.string.journal_photo_offline
            JournalMessage.PHOTO_FAILED -> R.string.journal_photo_failed
            JournalMessage.PHOTO_DAY_CHANGED -> R.string.journal_photo_day_changed
            JournalMessage.DAY_NOT_SAVED -> R.string.journal_day_not_saved
        }
    val load = message == JournalMessage.LOAD_OFFLINE || message == JournalMessage.LOAD_FAILED
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(JournalTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            if (load) {
                TextButton(onClick = callbacks.retryLoad, modifier = Modifier.testTag(JournalTags.RETRY_LOAD)) {
                    Text(stringResource(R.string.journal_retry))
                }
            } else {
                TextButton(onClick = callbacks.dismissMessage) { Text(stringResource(R.string.journal_dismiss)) }
            }
        }
    }
}
