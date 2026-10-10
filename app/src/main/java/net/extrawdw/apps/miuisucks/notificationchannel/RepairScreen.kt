package net.extrawdw.apps.miuisucks.notificationchannel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class RepairUiState(
    val status: String = "",
    val detail: String = "",
    val apps: List<AppEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val plans: List<String> = emptyList(),
    val connected: Boolean = false,
    val busy: Boolean = false,
    val running: Boolean = false,
    val recovery: Boolean = false,
    val needsGlobalLockscreen: Boolean = false,
) {
    val working: Boolean get() = busy || running
}

internal data class RepairActions(
    val connect: () -> Unit = {},
    val scan: () -> Unit = {},
    val stop: () -> Unit = {},
    val recover: () -> Unit = {},
    val preview: () -> Unit = {},
    val select: (Set<String>) -> Unit = {},
    val back: () -> Unit = {},
    val start: () -> Unit = {},
    val openGlobalLockscreen: () -> Unit = {},
    val isSilentMode: () -> Boolean = { false },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RepairContent(state: RepairUiState, actions: RepairActions = RepairActions()) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var silentWarning by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val visibleApps = remember(state.apps, query, showSystem) {
        state.apps.filter {
            (showSystem || !it.system) &&
                (it.name.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true))
        }
    }
    val plans = remember(state.plans) { state.plans.map(::JSONObject) }
    // Preserve each stage's scroll position when switching between selection and review.
    val appsGridState = rememberLazyGridState()
    val plansGridState = rememberLazyGridState()
    var wasRunning by remember { mutableStateOf(state.running) }
    LaunchedEffect(state.running) {
        if(wasRunning && !state.running)appsGridState.scrollToItem(0)
        wasRunning=state.running
    }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val direction = LocalLayoutDirection.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

    BackHandler(enabled = plans.isNotEmpty() && !state.working && !confirm, onBack = actions.back)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = systemInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                windowInsets = systemInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val startInset = innerPadding.calculateStartPadding(direction)
            val endInset = innerPadding.calculateEndPadding(direction)
            val availableWidth = maxWidth - startInset - endInset
            val gutter = if (availableWidth < 600.dp) 16.dp else 24.dp
            val sidePadding = maxOf(gutter, (availableWidth - 1200.dp) / 2)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(320.dp * fontScale),
                state = if (plans.isEmpty()) appsGridState else plansGridState,
                modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding).imePadding(),
                // Insets belong to scrollable content. Only the IME resizes the viewport.
                contentPadding = PaddingValues(
                    start = startInset + sidePadding,
                    end = endInset + sidePadding,
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "intro", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.intro), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.system_language), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.status)
                            if (state.needsGlobalLockscreen) TextButton(
                                onClick = actions.openGlobalLockscreen, enabled = !state.working,
                            ) { Text(stringResource(R.string.open_global_lockscreen)) }
                            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (state.detail.isNotEmpty()) {
                                TextButton(onClick = { showDetails = !showDetails }) { Text(stringResource(R.string.details)) }
                                if (showDetails) SelectionContainer {
                                    Text(state.detail, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                item(key = "connection", span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.connect, enabled = !state.working && !state.connected) {
                            Text(stringResource(R.string.connect))
                        }
                        OutlinedButton(onClick = actions.scan, enabled = state.connected && !state.working) {
                            Text(stringResource(R.string.scan))
                        }
                        if (state.running) Button(onClick = actions.stop) { Text(stringResource(R.string.cancel)) }
                    }
                }
                if (state.recovery && !state.running) {
                    item(key = "recovery", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.pending_recovery), color = MaterialTheme.colorScheme.error)
                            Button(onClick = actions.recover, enabled = state.connected && !state.working) {
                                Text(stringResource(R.string.recover))
                            }
                        }
                    }
                }
                if (plans.isNotEmpty()) {
                    item(key = "review_actions", span = { GridItemSpan(maxLineSpan) }) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { silentWarning = actions.isSilentMode(); confirm = true },
                                enabled = !state.working && !state.recovery && state.connected) {
                                Text(stringResource(R.string.repair))
                            }
                            TextButton(onClick = actions.back, enabled = !state.working) { Text(stringResource(R.string.back)) }
                        }
                    }
                    plans.forEach { plan ->
                        val pkg = plan.getString("package")
                        item(key = "package:$pkg", span = { GridItemSpan(maxLineSpan) }) {
                            Text(pkg, style = MaterialTheme.typography.titleMedium)
                        }
                        val channels = plan.getJSONArray("channels")
                        items(
                            count = channels.length(),
                            key = { "channel:$pkg:${channels.getJSONObject(it).getString("id")}" },
                            contentType = { "channel" },
                        ) { index -> ChannelCard(channels.getJSONObject(index)) }
                    }
                } else {
                    item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.search)) },
                            singleLine = true,
                            enabled = !state.working,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        )
                    }
                    item(key = "system_filter", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(showSystem, enabled = !state.working, role = Role.Checkbox) { showSystem = it }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = showSystem, onCheckedChange = null, enabled = !state.working)
                            Text(stringResource(R.string.show_system), Modifier.padding(start = 12.dp))
                        }
                    }
                    item(key = "selection_actions", span = { GridItemSpan(maxLineSpan) }) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { actions.select(visibleApps.filter { it.enabled }.map { it.pkg }.toSet()) },
                                enabled = !state.working,
                            ) { Text(stringResource(R.string.select_all)) }
                            TextButton(onClick = { actions.select(emptySet()) }, enabled = !state.working) {
                                Text(stringResource(R.string.clear_selection))
                            }
                        }
                    }
                    item(key = "preview_action", span = { GridItemSpan(maxLineSpan) }) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = { focusManager.clearFocus(); actions.preview() },
                                enabled = state.selected.isNotEmpty() && !state.working && !state.recovery && state.connected,
                            ) { Text(stringResource(R.string.preview)) }
                            Text(stringResource(R.string.selected_count, state.selected.size), Modifier.align(Alignment.CenterVertically))
                        }
                    }
                    items(visibleApps, key = { "app:${it.pkg}" }, contentType = { "app" }) { app ->
                        AppCard(app, app.pkg in state.selected, !state.working && app.enabled) { checked ->
                            actions.select(if (checked) state.selected + app.pkg else state.selected - app.pkg)
                        }
                    }
                    if (visibleApps.isEmpty() && !state.busy) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.empty)) }
                    }
                }
            }
            Spacer(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface))),
            )
        }
    }
    if (confirm && plans.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.confirm_body))
                    if (silentWarning) Text(stringResource(R.string.silent_mode_body), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // If silent mode changed while the dialog was open, show the warning
                        // before accepting a start that has not acknowledged it.
                        if (actions.isSilentMode() && !silentWarning) silentWarning = true
                        else { confirm = false; actions.start() }
                    },
                    enabled = !state.working && !state.recovery && state.connected,
                ) { Text(stringResource(if (silentWarning) R.string.start_anyway else R.string.repair)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.dismiss)) } },
        )
    }
}

@Composable
private fun AppCard(app: AppEntry, selected: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = onSelect)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium)
                Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (app.enabled) stringResource(R.string.count, app.count) else stringResource(R.string.disabled),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: JSONObject) {
    val eligible=channel.getBoolean("eligible")
    val id=channel.getString("id")
    OutlinedCard(
        Modifier.fillMaxWidth().semantics { if(!eligible)disabled() },
        colors=CardDefaults.outlinedCardColors(
            containerColor=if(eligible)MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor=if(eligible)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha=0.38f),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(channel.getString("name"), style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(stringResource(R.string.channel_id,id),style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace)
            }
            if (!eligible) {
                Text(stringResource(if(ChannelPolicy.isMiPush(id))R.string.skip_mipush else R.string.skip))
            } else {
                val on = stringResource(R.string.on)
                val off = stringResource(R.string.off)
                Text(stringResource(
                    R.string.channel_plan, channel.getInt("importance"),
                    if (channel.getBoolean("popup")) on else off,
                    if (channel.getBoolean("sound")) on else off,
                    if (channel.getBoolean("vibrate")) on else off,
                    if (channel.getBoolean("lockscreen")) on else off,
                ))
                Text(
                    stringResource(R.string.sound_uri,
                        if (channel.isNull("soundUri")) stringResource(R.string.silent) else channel.getString("soundUri")),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
