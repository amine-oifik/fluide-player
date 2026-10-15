package app.fluide.player.ui.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.SettingsValues
import app.fluide.player.data.ImportPhase
import app.fluide.player.data.ImportProgress
import app.fluide.player.data.SourceInfo
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.FocusableRow
import java.text.DateFormat
import java.util.Date

/**
 * Sources (playlists provided by the user's own service) and settings. The app ships no content:
 * adding a source requires the user to confirm they are entitled to it.
 */
@Composable
fun SourcesScreen(vm: MainViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.sources_title), style = MaterialTheme.typography.headlineMedium)
        }
        if (sources.isEmpty()) {
            item {
                Text(stringResource(R.string.sources_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(sources, key = { it.id }) { source ->
            SourceCard(
                source = source,
                active = source.id == ui.activeSourceId,
                progress = progress?.takeIf { it.sourceId == source.id },
                vm = vm,
            )
        }
        item { AddSourceForm(vm) }
        item { SettingsSection(settings, vm) }
        item {
            Text(
                vm.device.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceCard(source: SourceInfo, active: Boolean, progress: ImportProgress?, vm: MainViewModel) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (active) Text(stringResource(R.string.source_active), color = MaterialTheme.colorScheme.primary)
        }
        Text(
            pluralStringResource(R.plurals.source_channels, source.channelCount, source.channelCount) +
                if (source.programmeCount > 0) " · ${source.programmeCount} programmes" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (source.updatedAt > 0) {
                stringResource(
                    R.string.source_updated,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(source.updatedAt)),
                )
            } else {
                stringResource(R.string.source_never_updated)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        progress?.let { ImportLine(it) }
        source.lastError?.takeIf { progress == null }?.let {
            Text(stringResource(R.string.import_failed, it), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!active) Button(onClick = { vm.activateSource(source.id) }) { Text(stringResource(R.string.source_activate)) }
            OutlinedButton(onClick = { vm.refreshSource(source.id) }) { Text(stringResource(R.string.source_refresh)) }
            OutlinedButton(onClick = { vm.deleteSource(source.id) }) { Text(stringResource(R.string.source_delete)) }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = { vm.setMaxConnections(source.id, cycleConnections(source.maxConnections)) }) {
                Text(stringResource(R.string.source_max_connections, source.maxConnections))
            }
        }
    }
}

/** 1 -> 2 -> 3 -> 4 -> 1: connections the provider allows (preloading neighbours needs at least 2). */
private fun cycleConnections(current: Int): Int = if (current >= 4) 1 else current + 1

@Composable
private fun ImportLine(progress: ImportProgress) {
    val text = when {
        progress.failed -> stringResource(R.string.import_failed, progress.message ?: "")
        progress.notModified -> stringResource(R.string.import_not_modified)
        progress.done && progress.phase == ImportPhase.PLAYLIST -> stringResource(R.string.import_done, progress.count)
        progress.done -> stringResource(R.string.import_epg_done, progress.count)
        progress.phase == ImportPhase.PLAYLIST -> stringResource(R.string.import_running, progress.count)
        else -> stringResource(R.string.import_epg_running, progress.count)
    }
    Text(text, color = if (progress.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
}

@Composable
private fun AddSourceForm(vm: MainViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var epgUrl by rememberSaveable { mutableStateOf("") }
    var userAgent by rememberSaveable { mutableStateOf("") }
    var connections by rememberSaveable { mutableIntStateOf(1) }
    var attested by rememberSaveable { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addFileSource(uri, name)
    }

    Column(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.sources_add_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.source_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.source_url)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(epgUrl, { epgUrl = it }, singleLine = true, label = { Text(stringResource(R.string.source_epg_url)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(userAgent, { userAgent = it }, singleLine = true, label = { Text(stringResource(R.string.source_user_agent)) }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { connections = cycleConnections(connections) }) {
            Text(stringResource(R.string.source_max_connections, connections))
        }
        FocusableRow(onClick = { attested = !attested }, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = attested, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.source_attestation), style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = attested && url.isNotBlank(),
                onClick = {
                    vm.addUrlSource(name, url, epgUrl, userAgent, connections)
                    name = ""
                    url = ""
                    epgUrl = ""
                    userAgent = ""
                },
            ) { Text(stringResource(R.string.source_add_url)) }
            OutlinedButton(enabled = attested, onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.source_import_file))
            }
        }
    }
}

@Composable
private fun SettingsSection(settings: SettingsValues, vm: MainViewModel) {
    Column(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
        SettingSwitch(R.string.setting_resume, settings.resumeLastChannel) {
            vm.updateSettings { it.copy(resumeLastChannel = !it.resumeLastChannel) }
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

@Composable
private fun SettingSwitch(labelRes: Int, checked: Boolean, onToggle: () -> Unit) {
    FocusableRow(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
