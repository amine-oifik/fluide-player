package app.fluide.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.AspectMode
import app.fluide.player.BuildConfig
import app.fluide.player.R
import app.fluide.player.data.Section
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val back = remember { FocusRequester() }
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
                Card(stringResource(R.string.settings_about)) {
                    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.device.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
}

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
                            entry.hidden -> stringResource(R.string.category_hidden) to Color(0xFF9AA3BA)
                            entry.locked -> stringResource(R.string.category_locked) to Color(0xFFFFB74D)
                            else -> stringResource(R.string.category_visible) to Color(0xFF6BD49A)
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
