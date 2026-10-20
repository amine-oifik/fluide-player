package app.fluide.player.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.AspectMode
import app.fluide.player.BuildConfig
import app.fluide.player.R
import app.fluide.player.SettingsValues
import app.fluide.player.data.Section
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton
import app.fluide.player.ui.player.SleepTimerChoice
import app.fluide.player.ui.theme.FluideThemes
import app.fluide.player.ui.theme.themeName
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Survives the rebuild of the whole interface that applies a new colour theme (FluideTheme recreates
 * its content: remembered state and focus are lost).
 */
private object SettingsMemory {
    /** The theme row had the focus when the theme changed: give it back after the rebuild. */
    var focusTheme = false
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sleepEndsAt by vm.sleepTimerEndsAt.collectAsStateWithLifecycle()
    val back = remember { FocusRequester() }
    val language = remember { FocusRequester() }
    val theme = remember { FocusRequester() }
    // Set by the rows when they get the focus (focus requests are retried until they land).
    val focusedRow = remember { arrayOfNulls<FocusRequester>(1) }
    val activity = LocalActivity.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepEndsAt) {
        while (sleepEndsAt > 0L) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Backup: the system file picker (on a TV box without one, launch() throws).
    var noFilePicker by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }
    // Survives the recreation that applies a new language: the focus then comes back to the language row.
    var focusLanguage by rememberSaveable { mutableStateOf(false) }
    // One change at a time: a second OK before the recreation would skip a language.
    var switchingLanguage by remember { mutableStateOf(false) }
    ScreenSurface {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvButton("", onClick = vm::openHome, glyph = Glyph.BACK, focusRequester = back)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.home_settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Card(stringResource(R.string.settings_general)) {
                    SettingChoice(
                        label = stringResource(R.string.settings_language),
                        hint = stringResource(R.string.settings_language_hint),
                        value = languageName(settings.language),
                        focusRequester = language,
                        onFocused = { focusedRow[0] = language },
                    ) {
                        if (switchingLanguage) return@SettingChoice
                        switchingLanguage = true
                        vm.updateSettings { it.copy(language = nextLanguage(it.language)) }
                        focusLanguage = true
                        // The activity resources are localized in attachBaseContext: recreate it to apply.
                        val host = activity
                        if (host == null) switchingLanguage = false
                        host?.recreate()
                    }
                    SettingChoice(
                        label = stringResource(R.string.settings_theme),
                        hint = stringResource(R.string.settings_theme_hint),
                        value = stringResource(themeName(settings.theme)),
                        focusRequester = theme,
                        onFocused = { focusedRow[0] = theme },
                        leading = { ThemeSwatches(settings.theme) },
                    ) {
                        // The whole interface is rebuilt with the new colours: the focus comes back here.
                        SettingsMemory.focusTheme = true
                        vm.updateSettings { it.copy(theme = nextTheme(it.theme)) }
                    }
                    SettingSwitch(R.string.setting_resume, settings.resumeLastChannel) {
                        vm.updateSettings { it.copy(resumeLastChannel = !it.resumeLastChannel) }
                    }
                    SettingSwitch(R.string.setting_boot, settings.startOnBoot) {
                        vm.updateSettings { it.copy(startOnBoot = !it.startOnBoot) }
                    }
                    SettingSwitch(R.string.setting_clock, settings.clock24h) {
                        vm.updateSettings { it.copy(clock24h = !it.clock24h) }
                    }
                }
            }
            item {
                Card(stringResource(R.string.settings_player)) {
                    SettingAction(
                        stringResource(
                            R.string.setting_aspect,
                            stringResource(
                                when (settings.aspectMode) {
                                    AspectMode.FIT -> R.string.aspect_fit
                                    AspectMode.FILL -> R.string.aspect_fill
                                    AspectMode.ZOOM -> R.string.aspect_zoom
                                },
                            ),
                        ),
                    ) {
                        vm.updateSettings { it.copy(aspectMode = AspectMode.entries[(it.aspectMode.ordinal + 1) % AspectMode.entries.size]) }
                    }
                    SettingChoice(
                        label = stringResource(R.string.settings_subtitle_size),
                        hint = stringResource(R.string.settings_subtitle_size_hint),
                        value = stringResource(subtitleSizeName(settings.subtitleScale)),
                    ) {
                        vm.updateSettings { it.copy(subtitleScale = nextSubtitleScale(it.subtitleScale)) }
                    }
                    SettingChoice(
                        label = stringResource(R.string.settings_sleep),
                        hint = stringResource(R.string.settings_sleep_hint),
                        value = if (sleepEndsAt > 0L) {
                            stringResource(R.string.settings_sleep_left, SleepTimerChoice.minutesLeft(sleepEndsAt, now))
                        } else {
                            stringResource(R.string.player_sleep_off)
                        },
                    ) {
                        val current = SleepTimerChoice.current(vm.sleepTimerEndsAt.value, SLEEP_CYCLE, System.currentTimeMillis())
                        SleepTimerChoice.set(vm, SLEEP_CYCLE.firstOrNull { it > current } ?: 0)
                        now = System.currentTimeMillis()
                    }
                    SettingSwitch(R.string.setting_auto_next, settings.autoNextEpisode) {
                        vm.updateSettings { it.copy(autoNextEpisode = !it.autoNextEpisode) }
                    }
                    SettingSwitch(R.string.setting_afr, settings.autoFrameRate) {
                        vm.updateSettings { it.copy(autoFrameRate = !it.autoFrameRate) }
                    }
                    SettingSwitch(R.string.setting_tunneling, settings.tunneling) {
                        vm.updateSettings { it.copy(tunneling = !it.tunneling) }
                    }
                    SettingSwitch(R.string.setting_passthrough, settings.audioPassthrough) {
                        vm.updateSettings { it.copy(audioPassthrough = !it.audioPassthrough) }
                    }
                    SettingSwitch(R.string.setting_stats, settings.showStats) {
                        vm.updateSettings { it.copy(showStats = !it.showStats) }
                    }
                }
            }
            item {
                Card(stringResource(R.string.settings_parental)) {
                    SettingSwitch(R.string.setting_parental, settings.parentalEnabled) {
                        vm.setParental(!settings.parentalEnabled)
                    }
                    SettingAction(stringResource(R.string.setting_change_pin), glyph = Glyph.LOCK) { vm.changePin() }
                    SettingAction(stringResource(R.string.setting_categories_live), glyph = Glyph.TV) { vm.openCategories(Section.LIVE) }
                    SettingAction(stringResource(R.string.setting_categories_movies), glyph = Glyph.MOVIE) { vm.openCategories(Section.MOVIES) }
                    SettingAction(stringResource(R.string.setting_categories_series), glyph = Glyph.SERIES) { vm.openCategories(Section.SERIES) }
                }
            }
            item {
                Card(stringResource(R.string.settings_data)) {
                    SettingAction(stringResource(R.string.settings_clear_history)) { vm.clearHistory() }
                    SettingAction(stringResource(R.string.settings_clear_cache)) { vm.clearCache() }
                    SettingAction(stringResource(R.string.home_playlists), glyph = Glyph.PLAYLIST) { vm.openSources() }
                }
            }
            item {
                Card(stringResource(R.string.settings_backup)) {
                    Text(
                        stringResource(R.string.settings_backup_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(Glyph.LOCK, FluideColors.Warm, size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.settings_backup_warning),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = FluideColors.Warm,
                        )
                    }
                    SettingAction(stringResource(R.string.settings_backup_save), glyph = Glyph.DOWNLOAD) {
                        noFilePicker = false
                        try {
                            exportLauncher.launch(BACKUP_FILE_NAME)
                        } catch (e: ActivityNotFoundException) {
                            noFilePicker = true
                        }
                    }
                    SettingAction(stringResource(R.string.settings_backup_restore), glyph = Glyph.UPLOAD) {
                        noFilePicker = false
                        try {
                            importLauncher.launch(arrayOf("application/json", "*/*"))
                        } catch (e: ActivityNotFoundException) {
                            noFilePicker = true
                        }
                    }
                    if (noFilePicker) {
                        Text(
                            stringResource(R.string.settings_backup_no_picker),
                            style = MaterialTheme.typography.bodySmall,
                            color = FluideColors.Warm,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                        )
                    }
                }
            }
            item {
                Card(stringResource(R.string.settings_about)) {
                    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.device.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        val target = when {
            SettingsMemory.focusTheme -> theme
            focusLanguage -> language
            else -> back
        }
        SettingsMemory.focusTheme = false
        focusLanguage = false
        if (target === back) {
            runCatching { back.requestFocus() }
        } else if (!focusSoon(target) { focusedRow[0] === target }) {
            runCatching { back.requestFocus() }
        }
    }
}

/** File name offered by the system "save as" screen for a backup. */
private const val BACKUP_FILE_NAME = "fluide-player-backup.json"

/** Sleep timer durations cycled by the settings row (minutes; off after the last one). */
private val SLEEP_CYCLE: List<Int> = listOf(30, 60, 90, 120)

/** Subtitle sizes cycled by the settings row (same values as the player options) and their names. */
private val SUBTITLE_SIZES: List<Pair<Float, Int>> = listOf(
    0.85f to R.string.player_subtitle_small,
    1f to R.string.player_subtitle_normal,
    1.3f to R.string.player_subtitle_large,
    1.6f to R.string.player_subtitle_huge,
)

/** Index in [SUBTITLE_SIZES] of the size closest to [scale]. */
private fun subtitleSizeIndex(scale: Float): Int =
    SUBTITLE_SIZES.indices.minBy { abs(SUBTITLE_SIZES[it].first - scale) }

private fun subtitleSizeName(scale: Float): Int = SUBTITLE_SIZES[subtitleSizeIndex(scale)].second

private fun nextSubtitleScale(scale: Float): Float = SUBTITLE_SIZES[(subtitleSizeIndex(scale) + 1) % SUBTITLE_SIZES.size].first

/** Next colour theme of the cycle (SettingsValues.THEMES). */
private fun nextTheme(current: String): String {
    val themes = SettingsValues.THEMES
    return themes[(themes.indexOf(current).coerceAtLeast(0) + 1) % themes.size]
}

/** Requests the focus on [requester] until [done] (the row reports it), for a few frames at most. */
private suspend fun focusSoon(requester: FocusRequester, done: () -> Boolean): Boolean {
    repeat(10) {
        withFrameNanos { }
        if (done()) return true
        runCatching { requester.requestFocus() }
    }
    withFrameNanos { }
    return done()
}

/**
 * The colours of every theme in the cycle: the current one larger and ringed, so the user sees what
 * OK will bring next.
 */
@Composable
private fun ThemeSwatches(current: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (id in SettingsValues.THEMES) {
            val selected = id == current
            Swatch(id, if (selected) 22.dp else 12.dp, ringed = selected)
        }
    }
}

@Composable
private fun Swatch(themeId: String, size: Dp, ringed: Boolean) {
    val palette = FluideThemes.byId(themeId)
    Box(
        Modifier
            .size(size)
            .background(Brush.linearGradient(listOf(palette.blue, palette.cyan)), CircleShape)
            .border(if (ringed) 2.dp else 1.dp, if (ringed) Color.White else Color.White.copy(alpha = 0.25f), CircleShape),
    )
}

/** Next language of the cycle: system, Français, English, العربية. */
private fun nextLanguage(current: String): String {
    val languages = SettingsValues.LANGUAGES
    return languages[(languages.indexOf(current).coerceAtLeast(0) + 1) % languages.size]
}

/** Each language is named in its own language; "" is the system language. */
@Composable
private fun languageName(tag: String): String = stringResource(
    when (tag) {
        "fr" -> R.string.settings_language_fr
        "en" -> R.string.settings_language_en
        "ar" -> R.string.settings_language_ar
        else -> R.string.settings_language_system
    },
)

/** Manage categories: each one visible, locked (parental PIN) or hidden. */
@Composable
fun CategoriesScreen(vm: MainViewModel) {
    val state by vm.categories.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    ScreenSurface {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvButton("", onClick = { vm.onBack() }, glyph = Glyph.BACK)
                Spacer(Modifier.width(16.dp))
                val sectionName = stringResource(
                    when (state.section) {
                        Section.LIVE -> R.string.section_live
                        Section.MOVIES -> R.string.section_movies
                        Section.SERIES -> R.string.section_series
                    },
                )
                Text(stringResource(R.string.categories_title, sectionName), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.categories_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.widthIn(max = 900.dp)) {
                items(state.entries, key = { it.name }) { entry ->
                    FocusableRow(
                        onClick = { vm.cycleCategory(entry.name) },
                        focusRequester = if (entry == state.entries.firstOrNull()) first else null,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Text(entry.name, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(entry.count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(18.dp))
                        val (label, color) = when {
                            entry.hidden -> stringResource(R.string.category_hidden) to FluideColors.Muted
                            entry.locked -> stringResource(R.string.category_locked) to FluideColors.Warm
                            else -> stringResource(R.string.category_visible) to FluideColors.Cyan
                        }
                        if (entry.locked && !entry.hidden) AppIcon(Glyph.LOCK, color, size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = color, modifier = Modifier.width(110.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(state.entries.isNotEmpty()) { runCatching { first.requestFocus() } }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 1_000.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun SettingSwitch(labelRes: Int, checked: Boolean, onToggle: () -> Unit) {
    FocusableRow(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A setting with a few values: OK moves to the next one, the current value is shown on the right. */
@Composable
private fun SettingChoice(
    label: String,
    hint: String,
    value: String,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    FocusableRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        onFocused = onFocused,
    ) { focused ->
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(label)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                // Unspecified: the row's content color, readable on the focus highlight.
                color = if (focused) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Text(
            value,
            color = if (focused) Color.Unspecified else FluideColors.Cyan,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The label keeps room on narrow screens.
            modifier = Modifier.widthIn(max = 240.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingAction(label: String, glyph: Glyph? = null, onClick: () -> Unit) {
    FocusableRow(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (glyph != null) {
            AppIcon(glyph, MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(label, modifier = Modifier.weight(1f))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
