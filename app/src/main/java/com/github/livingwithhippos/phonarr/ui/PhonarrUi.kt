package com.github.livingwithhippos.phonarr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.livingwithhippos.phonarr.core.data.IndexerConfig

/** Sub-screens of the Indexers tab. */
sealed interface IndexerRoute {
    data object List : IndexerRoute
    data object Pick : IndexerRoute
    data class Edit(val definitionId: String, val existing: IndexerConfig?) : IndexerRoute
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonarrUi(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var route by remember { mutableStateOf<IndexerRoute>(IndexerRoute.List) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    BackHandler(enabled = showSettings) { showSettings = false }

    BackHandler(enabled = !showSettings && tab == 1 && route != IndexerRoute.List) {
        route = if (route is IndexerRoute.Edit && (route as IndexerRoute.Edit).existing == null) IndexerRoute.Pick else IndexerRoute.List
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (showSettings) "Settings" else "Phonarr") },
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (!showSettings) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (showSettings) {
            Column(Modifier.padding(padding)) { SettingsScreen(vm) }
            return@Scaffold
        }
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Server", "Indexers", "Search").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> ServerScreen(vm)
                1 -> when (val r = route) {
                    IndexerRoute.List -> IndexerListScreen(
                        vm,
                        onAdd = { route = IndexerRoute.Pick },
                        onEdit = { route = IndexerRoute.Edit(it.definitionId, it) },
                    )
                    IndexerRoute.Pick -> DefinitionPickerScreen(
                        vm,
                        onPick = { route = IndexerRoute.Edit(it, null) },
                        onBack = { route = IndexerRoute.List },
                    )
                    is IndexerRoute.Edit -> IndexerEditScreen(
                        vm, r.definitionId, r.existing,
                        onDone = { route = IndexerRoute.List },
                    )
                }
                else -> SearchScreen(vm)
            }
        }
    }
}
