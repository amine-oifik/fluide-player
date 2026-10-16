package app.fluide.player.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.ScreenSurface

/** Global search (placeholder layout: replaced by the search screen design). */
@Composable
fun SearchScreen(vm: MainViewModel) {
    val state by vm.globalSearch.state.collectAsStateWithLifecycle()
    ScreenSurface {
        Column(Modifier.fillMaxSize().padding(48.dp)) {
            OutlinedTextField(state.query, vm::searchAll, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("${state.channels.size} · ${state.movies.size} · ${state.series.size}")
        }
    }
}
