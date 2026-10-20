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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.ImportPhase
import app.fluide.player.data.ImportProgress
import app.fluide.player.data.SourceInfo
import app.fluide.player.data.SourceType
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * Playlists provided by the user's own service: Xtream Codes accounts, M3U links and files. The app
 * ships no content: adding a playlist requires the user to confirm they are entitled to it.
 */
@Composable
fun SourcesScreen(vm: MainViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    ScreenSurface {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sources.isNotEmpty()) {
                        TvButton("", onClick = vm::openHome, glyph = Glyph.BACK)
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(stringResource(R.string.sources_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
            if (sources.isEmpty()) {
                item { Text(stringResource(R.string.sources_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { AddSourceForm(vm) }
            items(sources, key = { it.id }) { source ->
                SourceCard(
                    source = source,
                    active = source.id == ui.activeSourceId,
                    progress = progress?.takeIf { it.sourceId == source.id },
                    vm = vm,
                )
            }
            item {
                Text(vm.device.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SourceCard(source: SourceInfo, active: Boolean, progress: ImportProgress?, vm: MainViewModel) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .widthIn(max = 1_000.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(if (source.type == SourceType.XTREAM) Glyph.TV else Glyph.PLAYLIST, MaterialTheme.colorScheme.primary, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                stringResource(
                    when (source.type) {
                        SourceType.XTREAM -> R.string.source_type_xtream
                        SourceType.URL -> R.string.source_type_m3u
                        SourceType.FILE -> R.string.source_type_file
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active) {
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.source_active), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            buildList {
                add(pluralStringResource(R.plurals.source_channels, source.channelCount, source.channelCount))
                add(pluralStringResource(R.plurals.data_source_movies, source.movieCount, source.movieCount))
                add(pluralStringResource(R.plurals.data_source_series, source.seriesCount, source.seriesCount))
                if (source.programmeCount > 0) {
                    add(pluralStringResource(R.plurals.data_source_programmes, source.programmeCount, source.programmeCount))
                }
            }.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (source.type == SourceType.XTREAM) {
            val expires = source.expiresAtSec
            val parts = ArrayList<String>(3)
            parts += if (expires != null) {
                stringResource(R.string.home_expires, DateFormat.getDateInstance(DateFormat.LONG).format(Date(expires * 1_000)))
            } else {
                stringResource(R.string.home_unlimited)
            }
            val status = source.accountStatus
            if (status != null) parts += stringResource(R.string.home_status, status)
            val activeCons = source.activeConnections
            if (activeCons != null) parts += stringResource(R.string.home_connections, activeCons, source.maxConnections)
            Text(parts.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
            if (!active) TvButton(stringResource(R.string.source_activate), onClick = { vm.activateSource(source.id) }, primary = true)
            TvButton(stringResource(R.string.source_refresh), onClick = { vm.refreshSource(source.id) }, glyph = Glyph.REFRESH)
            TvButton(stringResource(R.string.source_delete), onClick = { vm.deleteSource(source.id) })
            if (source.type == SourceType.XTREAM) {
                TvButton(
                    stringResource(R.string.source_format, if (source.streamFormat == "m3u8") "HLS (m3u8)" else "MPEG-TS"),
                    onClick = { vm.setStreamFormat(source.id, if (source.streamFormat == "m3u8") "ts" else "m3u8") },
                )
            } else {
                TvButton(
                    stringResource(R.string.source_max_connections, source.maxConnections),
                    onClick = { vm.setMaxConnections(source.id, cycleConnections(source.maxConnections)) },
                )
            }
        }
        EpgOffsetControl(source.epgOffsetMinutes) { minutes -> vm.setEpgOffset(source.id, minutes) }
    }
}

/**
 * Shift of the playlist's guide, for providers whose XMLTV times are off: − / + move it by 30 minutes
 * (12 hours at most either way). The buttons stay enabled at the bounds so the D-pad focus never drops.
 */
@Composable
private fun EpgOffsetControl(offsetMinutes: Int, onChange: (Int) -> Unit) {
    val earlier = stringResource(R.string.data_epg_offset_earlier)
    val later = stringResource(R.string.data_epg_offset_later)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Glyph.GUIDE, MaterialTheme.colorScheme.primary, size = 20.dp)
        Text(stringResource(R.string.data_epg_offset), fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(4.dp))
        TvButton(
            "\u2212",
            onClick = {
                val next = (offsetMinutes - EPG_OFFSET_STEP).coerceAtLeast(-EPG_OFFSET_MAX)
                if (next != offsetMinutes) onChange(next)
            },
            modifier = Modifier.semantics { contentDescription = earlier },
        )
        Text(
            epgOffsetLabel(offsetMinutes),
            color = if (offsetMinutes == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 84.dp),
        )
        TvButton(
            "+",
            onClick = {
                val next = (offsetMinutes + EPG_OFFSET_STEP).coerceAtMost(EPG_OFFSET_MAX)
                if (next != offsetMinutes) onChange(next)
            },
            modifier = Modifier.semantics { contentDescription = later },
        )
    }
    Text(
        stringResource(R.string.data_epg_offset_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Aucun", "+30 min", "+2 h", "−1 h 30". */
@Composable
private fun epgOffsetLabel(minutes: Int): String {
    if (minutes == 0) return stringResource(R.string.data_epg_offset_none)
    val sign = if (minutes > 0) "+" else "\u2212"
    val hours = abs(minutes) / 60
    val rest = abs(minutes) % 60
    return when {
        hours == 0 -> stringResource(R.string.data_epg_offset_minutes, sign, rest)
        rest == 0 -> stringResource(R.string.data_epg_offset_hours, sign, hours)
        else -> stringResource(R.string.data_epg_offset_hours_minutes, sign, hours, rest)
    }
}

private const val EPG_OFFSET_STEP = 30
private const val EPG_OFFSET_MAX = 720

/** 1 -> 2 -> 3 -> 4 -> 1: connections the provider allows (preloading neighbours needs at least 2). */
private fun cycleConnections(current: Int): Int = if (current >= 4) 1 else current + 1

@Composable
private fun ImportLine(progress: ImportProgress) {
    val text = when {
        progress.failed -> stringResource(R.string.import_failed, progress.message ?: "")
        progress.notModified -> stringResource(R.string.import_not_modified)
        progress.done && progress.phase == ImportPhase.PLAYLIST ->
            stringResource(R.string.import_done_full, progress.count, progress.movies, progress.series)
        progress.done -> stringResource(R.string.import_epg_done, progress.count)
        progress.phase == ImportPhase.PLAYLIST -> stringResource(R.string.import_running, progress.count)
        else -> stringResource(R.string.import_epg_running, progress.count)
    }
    Text(text, color = if (progress.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    if (!progress.done) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun AddSourceForm(vm: MainViewModel) {
    val error by vm.sourceError.collectAsStateWithLifecycle()
    val adding by vm.addingSource.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var hls by rememberSaveable { mutableStateOf(false) }
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
            .widthIn(max = 1_000.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.sources_add_title), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.source_tab_xtream, R.string.source_tab_m3u, R.string.source_tab_file).forEachIndexed { index, label ->
                FocusableRow(onClick = { tab = index }, selected = tab == index, modifier = Modifier.height(44.dp)) {
                    Text(stringResource(label))
                }
            }
        }
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.source_name)) }, modifier = Modifier.fillMaxWidth())
        when (tab) {
            0 -> {
                OutlinedTextField(
                    server, { server = it }, singleLine = true,
                    label = { Text(stringResource(R.string.source_server)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        username, { username = it }, singleLine = true,
                        label = { Text(stringResource(R.string.source_username)) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        password, { password = it }, singleLine = true,
                        label = { Text(stringResource(R.string.source_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.weight(1f),
                    )
                }
                TvButton(stringResource(R.string.source_format, if (hls) "HLS (m3u8)" else "MPEG-TS"), onClick = { hls = !hls })
            }
            1 -> {
                OutlinedTextField(
                    url, { url = it }, singleLine = true,
                    label = { Text(stringResource(R.string.source_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.source_m3u_xtream_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(epgUrl, { epgUrl = it }, singleLine = true, label = { Text(stringResource(R.string.source_epg_url)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(userAgent, { userAgent = it }, singleLine = true, label = { Text(stringResource(R.string.source_user_agent)) }, modifier = Modifier.fillMaxWidth())
                TvButton(stringResource(R.string.source_max_connections, connections), onClick = { connections = cycleConnections(connections) })
            }
        }
        FocusableRow(onClick = { attested = !attested }, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = attested, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.source_attestation), style = MaterialTheme.typography.bodyMedium)
        }
        if (adding) {
            Text(stringResource(R.string.source_checking), color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { Text(stringResource(R.string.source_add_error, it), color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> TvButton(
                    stringResource(R.string.source_login),
                    enabled = attested && !adding && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    primary = true,
                    onClick = {
                        vm.addXtreamSource(name, server, username, password, if (hls) "m3u8" else "ts")
                        name = ""
                    },
                )
                1 -> TvButton(
                    stringResource(R.string.source_add_url),
                    enabled = attested && !adding && url.isNotBlank(),
                    primary = true,
                    onClick = {
                        vm.addUrlSource(name, url, epgUrl, userAgent, connections)
                        name = ""
                        url = ""
                        epgUrl = ""
                        userAgent = ""
                    },
                )
                else -> TvButton(
                    stringResource(R.string.source_import_file),
                    enabled = attested,
                    primary = true,
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                )
            }
        }
    }
}
