package cc.buessow.mtpcommander

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class CommanderItem(
    val id: String, val name: String, val directory: Boolean, val size: Long, val modified: Long
)

internal data class CommanderCrumb(val label: String, val depth: Int)

internal data class CommanderPane(
    val title: String, val path: List<CommanderCrumb>, val entries: List<CommanderItem>,
    val status: String, val busy: Boolean, val canBack: Boolean,
    val actionLabel: String, val navigationKey: String,
    val pickable: Boolean = false, val progress: Float? = null
)

internal enum class CommanderMode { NONE, COPY, DELETE, OPEN }

/** File list order; folders always come first. */
private enum class SortKey { NAME, SIZE, DATE }

private data class Sort(val key: SortKey, val descending: Boolean) {
    fun apply(entries: List<CommanderItem>): List<CommanderItem> {
        val byKey = when (key) {
            SortKey.NAME -> compareBy<CommanderItem> { it.name.lowercase() }
            SortKey.SIZE -> compareBy { it.size }
            SortKey.DATE -> compareBy { it.modified }
        }.thenBy { it.name.lowercase() }
        return entries.sortedWith(compareBy<CommanderItem> { !it.directory }
            .then(if (descending) byKey.reversed() else byKey))
    }

    /** Tapping the current column reverses it; a new column starts with names A–Z, sizes and dates largest first. */
    fun toggle(column: SortKey) = if (column == key) copy(descending = !descending)
        else Sort(column, descending = column != SortKey.NAME)
}

/** A search match; [folder] is its location relative to the folder the search started in. */
internal data class SearchHit(val item: CommanderItem, val folder: String)

/** While [active], the folder list is replaced by the query and the hits below it. */
internal data class SearchState(val active: Boolean, val root: String, val query: String,
    val hits: List<SearchHit>, val busy: Boolean, val status: String)

/** While [active], pane [target] shows a field for the name of a folder to create in it. */
internal data class NewFolderState(val active: Boolean, val target: Int, val name: String, val location: String)

/** Screen colours; [onActive] and [onAccent] are for text on the active and accent backgrounds. */
private class Palette(val paper: Color, val ink: Color, val muted: Color, val divider: Color, val accent: Color,
    val active: Color, val onActive: Color, val onActiveDetail: Color, val onAccent: Color,
    val selected: Color)

private val LightPalette = Palette(paper = Color(0xFFF5F0E8), ink = Color(0xFF2C455D), muted = Color(0xFF77716A),
    divider = Color(0xFFD9D0C6), accent = Color(0xFFA75030), active = Color(0xFF2C455D), onActive = Color(0xFFF5F0E8),
    onActiveDetail = Color(0xFFE6DBD0), onAccent = Color(0xFFF5F0E8), selected = Color(0xFFF0DCC9))

private val DarkPalette = Palette(paper = Color(0xFF1B1A18), ink = Color(0xFFE4DDD3), muted = Color(0xFF9A938B),
    divider = Color(0xFF3A3632), accent = Color(0xFFE0875F), active = Color(0xFF34506A), onActive = Color(0xFFF2ECE4),
    onActiveDetail = Color(0xFFC3CFDA), onAccent = Color(0xFF1B1A18), selected = Color(0xFF4A3B30))

private val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Follows the phone's dark mode setting. */
@Composable
internal fun CommanderTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (isSystemInDarkTheme()) DarkPalette else LightPalette,
        content = content)
}

private val paper: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.paper
private val ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
private val muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
private val divider: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.divider
private val accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
private val active: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.active
private val onActive: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onActive
private val onActiveDetail: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onActiveDetail
private val onAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent
private val selected: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.selected
private val heading = FontFamily.SansSerif

@Composable
internal fun CommanderScreen(
    panes: List<CommanderPane>, activePane: Int, onActivate: (Int) -> Unit,
    onOpen: (Int, CommanderItem) -> Unit,
    onNavigate: (Int, Int) -> Unit, onAction: (Int) -> Unit, onRefresh: () -> Unit,
    showPhoneAccessExplanation: Boolean, onConfirmPhoneAccess: () -> Unit,
    onDismissPhoneAccess: () -> Unit, mode: CommanderMode, onMode: (CommanderMode) -> Unit,
    selection: List<CommanderItem>, picked: Set<String>,
    onPick: (Int, List<CommanderItem>) -> Unit, onOpenFile: (Int, CommanderItem) -> Unit, onOpenHit: (String) -> Unit,
    onExecute: () -> Unit, onClear: () -> Unit, busy: Boolean,
    search: SearchState, onSearchToggle: () -> Unit, onSearchQuery: (String) -> Unit, onSearch: () -> Unit,
    onPickHit: (String) -> Unit, newFolder: NewFolderState, onNewFolderToggle: () -> Unit,
    onNewFolderName: (String) -> Unit, onCreateFolder: () -> Unit
) {
    val density = LocalDensity.current
    val buttonBounds = remember { mutableStateListOf(Rect.Zero, Rect.Zero, Rect.Zero, Rect.Zero, Rect.Zero) }
    val context = LocalContext.current
    // Each instruction balloon is shown once after install; the preference records which ones were seen.
    val hints = remember { context.getSharedPreferences("hints", android.content.Context.MODE_PRIVATE) }
    val balloon = when {
        mode == CommanderMode.NONE -> null
        mode == CommanderMode.OPEN -> "open-1"
        search.active && mode == CommanderMode.COPY -> "search-copy-3"
        search.active -> "search-delete-3"
        mode == CommanderMode.COPY -> "copy-5"
        else -> "delete-4"
    }
    var balloonHidden by remember(balloon) { mutableStateOf(balloon == null || hints.getBoolean(balloon, false)) }
    LaunchedEffect(balloon) { if (balloon != null && !balloonHidden) hints.edit().putBoolean(balloon, true).apply() }
    val view = remember { context.getSharedPreferences("view", android.content.Context.MODE_PRIVATE) }
    var sort by remember {
        mutableStateOf(Sort(runCatching { SortKey.valueOf(view.getString("sort", null)!!) }.getOrDefault(SortKey.NAME),
            view.getBoolean("descending", false)))
    }
    val onSort = { column: SortKey ->
        sort = sort.toggle(column)
        view.edit().putString("sort", sort.key.name).putBoolean("descending", sort.descending).apply()
    }
    // Scroll positions live here, not in Pane, so they survive a pane being swapped for the search panel.
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    listStates.keys.retainAll(panes.map { it.navigationKey }.toSet())
    if (showPhoneAccessExplanation) {
        AlertDialog(
            onDismissRequest = onDismissPhoneAccess,
            title = { Text("Browse phone storage") },
            text = { Text("To show files in the phone's shared storage without choosing folders one by one, MTP Commander needs all-files access. In Settings, enable ‘Allow access to manage all files’ for this app. Browsing is read-only; you can continue using an MTP device if you decline.") },
            confirmButton = { TextButton(onClick = onConfirmPhoneAccess) { Text("Open Settings") } },
            dismissButton = { TextButton(onClick = onDismissPhoneAccess) { Text("Not now") } }
        )
    }
    // In landscape the copy target sits to the right and the title shares a row with the tool buttons.
    val wide = LocalConfiguration.current.run { screenWidthDp > screenHeightDp }
    val title = @Composable {
        Text("MTP COMMANDER", fontFamily = heading, fontWeight = FontWeight.Black,
            fontSize = 23.sp, letterSpacing = (-0.7).sp, color = ink, maxLines = 1)
    }
    val paneTitleAndRefresh = @Composable {
        Text(panes[activePane].title, color = accent, fontSize = 10.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(106.dp),
            textAlign = TextAlign.End)
        Text(" ↻ ", modifier = Modifier.clickable(onClick = onRefresh).padding(5.dp),
            fontSize = 22.sp, color = accent)
    }
    val tools: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier.border(BorderStroke(0.5.dp, divider))) {
            listOf(
                ToolButton("Copy", "▣", mode == CommanderMode.COPY) { onMode(CommanderMode.COPY) },
                ToolButton("Delete", "×", mode == CommanderMode.DELETE) { onMode(CommanderMode.DELETE) },
                ToolButton("Open", "↗", mode == CommanderMode.OPEN) { onMode(CommanderMode.OPEN) },
                ToolButton("Search", "⌕", search.active, onSearchToggle),
                ToolButton("New folder", "+", newFolder.active, onNewFolderToggle)
            ).forEachIndexed { index, (label, icon, on, onClick) ->
                Column(Modifier.weight(1f).height(56.dp)
                    .onGloballyPositioned { buttonBounds[index] = it.boundsInRoot() }
                    .background(if (on) active else paper)
                    .clickable(onClick = onClick)
                    .border(BorderStroke(0.5.dp, divider)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(icon, fontSize = 19.sp, color = if (on) onActive else muted)
                    Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp, color = if (on) onActive else muted)
                }
            }
        }
    }
    // Pane 0 is the folder list picked from, or the search over it; pane 1 is the copy target, shown in COPY only.
    val paneSlot: @Composable (Int, Modifier) -> Unit = { index, paneModifier ->
        val pane = panes[index]
        if (search.active && index == 0) SearchPanel(search, onSearchQuery, onSearch, picked,
            pickEnabled = mode == CommanderMode.COPY || mode == CommanderMode.DELETE,
            onPick = { id -> balloonHidden = true; onPickHit(id) },
            onOpen = if (mode == CommanderMode.OPEN) ({ id -> balloonHidden = true; onOpenHit(id) }) else null,
            modifier = paneModifier)
        else Column(paneModifier) {
            if (newFolder.active && newFolder.target == index)
                NewFolderInput(newFolder, onNewFolderName, onCreateFolder)
            key(index, pane.navigationKey) {
                Pane(
                    listState = listStates.getOrPut(pane.navigationKey) { LazyListState() },
                    title = if (index == 1) "TO ${pane.title}" else pane.title,
                    symbol = if (index == 0) "▯" else "▣", path = pane.path,
                    entries = remember(pane.entries, sort) { sort.apply(pane.entries) }, sort = sort, onSort = onSort,
                    isActive = activePane == index, isBusy = pane.busy, progress = pane.progress,
                    emptyMessage = pane.status,
                    actionLabel = pane.actionLabel, onAction = { onAction(index) },
                    onNavigate = { depth -> onActivate(index); onNavigate(index, depth) },
                    onOpen = { item ->
                        if (item.directory) onOpen(index, item)
                        else if (mode == CommanderMode.OPEN) { balloonHidden = true; onOpenFile(index, item) }
                        else onActivate(index)
                    },
                    onActivate = { onActivate(index) },
                    pickEnabled = index == 0 && (mode == CommanderMode.COPY || mode == CommanderMode.DELETE) &&
                        pane.pickable && !pane.busy,
                    picked = picked,
                    onPick = { items -> onActivate(index); balloonHidden = true; onPick(index, items) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    val slots = if (mode == CommanderMode.COPY) panes.indices else 0..0
    Box(Modifier.fillMaxSize().background(paper)) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (wide) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                title()
                tools(Modifier.weight(1f).padding(start = 16.dp))
                paneTitleAndRefresh()
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                slots.forEach { slot ->
                    if (slot == 1) Spacer(Modifier.fillMaxHeight().width(1.dp).background(divider))
                    paneSlot(slot, Modifier.weight(1f))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                title()
                Spacer(Modifier.weight(1f))
                paneTitleAndRefresh()
            }
            tools(Modifier.fillMaxWidth())
            slots.forEach { slot ->
                if (slot == 1) Spacer(Modifier.fillMaxWidth().height(1.dp).background(divider))
                paneSlot(slot, Modifier.weight(1f))
            }
        }
        if (mode == CommanderMode.COPY || mode == CommanderMode.DELETE)
            ExecuteBar(copy = mode == CommanderMode.COPY, selection, onExecute = onExecute, onClear = onClear, busy = busy)
        Text(panes[activePane].status,
            Modifier.fillMaxWidth().height(if (wide) 28.dp else 44.dp).padding(horizontal = 12.dp, vertical = 5.dp),
            color = muted, fontSize = 11.sp, maxLines = if (wide) 1 else 2, overflow = TextOverflow.Ellipsis)
    }
    val button = buttonBounds[when (mode) { CommanderMode.DELETE -> 1; CommanderMode.OPEN -> 2; else -> 0 }]
    if (balloon != null && !balloonHidden && button != Rect.Zero) {
        val target = if (wide) "on the right" else "below"
        val message = when (balloon) {
            "open-1" -> "Tap a file to open it in another app. Files on a watch or in Google Drive are copied to " +
                "the phone first."
            "search-copy-3" -> "Tick search results to copy them. Open the folder to copy into in the list $target, " +
                "then press COPY."
            "search-delete-3" -> "Tick search results to delete them. Nothing is deleted until you press DELETE."
            "copy-5" -> "Tick files to copy them, or hold a file or folder. Open the folder to copy into in the list " +
                "$target, then press COPY."
            else -> "Tick files to delete them, or hold a file or folder. Nothing is deleted until you press DELETE."
        }
        Column(Modifier.offset { IntOffset(0, button.bottom.toInt()) }.fillMaxWidth()
            .clickable { balloonHidden = true }) {
            // Outlined in paper so the balloon stands out on the dark pane header it overlaps.
            val arrowFill = ink
            val arrowEdge = paper
            Canvas(Modifier.offset { IntOffset(button.center.x.toInt() - with(density) { 9.dp.roundToPx() }, 0) }
                .size(18.dp, 11.dp).zIndex(1f)) {
                val arrow = Path().apply {
                    moveTo(size.width / 2, 0f); lineTo(size.width, size.height + 2.dp.toPx())
                    lineTo(0f, size.height + 2.dp.toPx()); close()
                }
                drawPath(arrow, arrowFill)
                drawPath(arrow, arrowEdge, style = Stroke(2.dp.toPx()))
            }
            Column(Modifier.offset(y = (-2).dp).padding(horizontal = 12.dp).fillMaxWidth()
                .background(ink, RoundedCornerShape(8.dp)).border(2.dp, paper, RoundedCornerShape(8.dp))
                .padding(12.dp)) {
                Text(message, color = paper, fontSize = 13.sp)
                Text("TAP TO HIDE", color = paper.copy(alpha = 0.75f), fontSize = 9.sp, letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    }
}

private val inputColors @Composable get() = OutlinedTextFieldDefaults.colors(focusedTextColor = ink,
    unfocusedTextColor = ink, focusedBorderColor = accent, unfocusedBorderColor = muted, cursorColor = accent,
    focusedPlaceholderColor = muted, unfocusedPlaceholderColor = muted)

/** The search box over its results; the search runs below the folder that was open in the list. */
@Composable
private fun SearchPanel(search: SearchState, onQuery: (String) -> Unit, onSearch: () -> Unit, picked: Set<String>,
    pickEnabled: Boolean, onPick: (String) -> Unit, onOpen: ((String) -> Unit)?, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier.background(paper)) {
        PanelHeader("⌕", "SEARCH", search.root)
        OutlinedTextField(search.query, onQuery,
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).focusRequester(focus),
            singleLine = true, placeholder = { Text("Name or pattern, e.g. *.fit") },
            trailingIcon = { TextButton(onClick = onSearch) { Text("SEARCH", color = accent, fontWeight = FontWeight.Bold) } },
            // File names and patterns are not words; autocorrect turns "img" into "omg".
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }), colors = inputColors)
        LazyColumn(Modifier.weight(1f)) {
            items(search.hits, key = { it.item.id }) { hit ->
                val isPicked = pickEnabled && hit.item.id in picked
                Row(Modifier.fillMaxWidth().height(48.dp).background(if (isPicked) selected else paper)
                    .combinedClickable(enabled = pickEnabled || onOpen != null && !hit.item.directory,
                        onLongClick = if (pickEnabled) ({ onPick(hit.item.id) }) else null) {
                        if (pickEnabled) onPick(hit.item.id) else onOpen?.invoke(hit.item.id)
                    }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pickEnabled) PickBox(isPicked) { onPick(hit.item.id) }
                    ItemIcon(hit.item, Modifier.width(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hit.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = ink,
                            fontWeight = if (hit.item.directory) FontWeight.SemiBold else FontWeight.Normal)
                        Text(hit.folder.ifEmpty { "." }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 10.sp, color = muted)
                    }
                }
                Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(divider.copy(alpha = 0.5f)))
            }
            if (search.hits.isEmpty() && !search.busy) item {
                Text("Searches all subfolders. Text matches any part of a name. Use * for any characters and ? for " +
                    "one character; a pattern must then match the whole name. Case is ignored.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = muted, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().height(27.dp).border(BorderStroke(0.5.dp, divider))
            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (search.busy) CircularProgressIndicator(Modifier.size(12.dp), color = accent, strokeWidth = 1.5.dp)
            if (search.busy) Spacer(Modifier.width(6.dp))
            Text(search.status, fontSize = 11.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A name field above the list the folder is created in. */
@Composable
private fun NewFolderInput(state: NewFolderState, onName: (String) -> Unit, onCreate: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(state.name, onName,
        Modifier.fillMaxWidth().background(paper).padding(horizontal = 10.dp, vertical = 6.dp).focusRequester(focus),
        singleLine = true, placeholder = { Text("New folder in ${state.location}", maxLines = 1,
            overflow = TextOverflow.Ellipsis) },
        trailingIcon = { TextButton(onClick = onCreate) { Text("CREATE", color = accent, fontWeight = FontWeight.Bold) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None),
        keyboardActions = KeyboardActions(onDone = { onCreate() }), colors = inputColors)
}

private data class ToolButton(val label: String, val icon: String, val on: Boolean, val onClick: () -> Unit)

@Composable
private fun PanelHeader(symbol: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().height(37.dp).background(active).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(symbol, color = onActive, fontSize = 17.sp)
        Spacer(Modifier.width(7.dp))
        Text(title, color = onActive, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Spacer(Modifier.width(10.dp))
        Text(detail, Modifier.weight(1f), color = onActiveDetail, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
    }
}

/** Copies or deletes everything ticked, in any folder; the label counts it. */
@Composable
private fun ExecuteBar(copy: Boolean, selection: List<CommanderItem>, onExecute: () -> Unit, onClear: () -> Unit,
    busy: Boolean) {
    val enabled = selection.isNotEmpty() && !busy
    val color = if (copy) active else accent
    val onColor = if (copy) onActive else onAccent
    // Centred rather than padded to a fixed height, so larger font sizes are not cut off.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(96.dp).fillMaxHeight().heightIn(min = 48.dp).border(BorderStroke(0.5.dp, divider))
            .clickable(enabled = enabled, onClick = onClear), contentAlignment = Alignment.Center) {
            Text("CLEAR", Modifier.padding(vertical = 8.dp), color = if (enabled) ink else muted, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, textAlign = TextAlign.Center)
        }
        Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
            .background(if (enabled) color else divider)
            .clickable(enabled = enabled, onClick = onExecute), contentAlignment = Alignment.Center) {
            val verb = if (copy) "COPY" else "DELETE"
            Text(if (busy && selection.isNotEmpty()) "${if (copy) "COPYING" else "DELETING"}…"
                else "$verb ${selectionLabel(LocalContext.current, selection)}",
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                color = onColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "3 FILES · 1.2 MB", "2 FILES + 1 FOLDER · 40 kB"; folder sizes are unknown until copied, so they are only counted. */
private fun selectionLabel(context: android.content.Context, selection: List<CommanderItem>): String {
    val files = selection.filter { !it.directory }
    val folders = selection.size - files.size
    fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "S"}"
    return listOfNotNull(count(files.size, "FILE").takeIf { files.isNotEmpty() || folders == 0 },
        count(folders, "FOLDER").takeIf { folders > 0 }).joinToString(" + ") +
        " · " + Formatter.formatShortFileSize(context, files.sumOf { it.size.coerceAtLeast(0) })
}

@Composable
private fun PickBox(checked: Boolean, onToggle: () -> Unit) {
    // Rows are 40dp high, smaller than Material's 48dp touch target; the row itself is a target too.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Checkbox(checked, { onToggle() }, Modifier.width(36.dp).fillMaxHeight(),
            colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = muted, checkmarkColor = onAccent))
    }
}

@Composable
private fun ItemIcon(item: CommanderItem, modifier: Modifier = Modifier) {
    Text(if (item.directory) "▱" else "▤", modifier,
        color = if (item.directory) accent else ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center)
}

@Composable
private fun Pane(
    listState: LazyListState, title: String, symbol: String, path: List<CommanderCrumb>, entries: List<CommanderItem>,
    sort: Sort, onSort: (SortKey) -> Unit, isActive: Boolean, isBusy: Boolean, emptyMessage: String, progress: Float?,
    actionLabel: String, onAction: () -> Unit, onNavigate: (Int) -> Unit,
    onOpen: (CommanderItem) -> Unit, onActivate: () -> Unit, pickEnabled: Boolean, picked: Set<String>,
    onPick: (List<CommanderItem>) -> Unit, modifier: Modifier
) {
    val context = LocalContext.current
    val pathScroll = rememberScrollState()
    LaunchedEffect(path, pathScroll.maxValue) { pathScroll.scrollTo(pathScroll.maxValue) }
    Column(modifier.background(paper)) {
        Row(Modifier.fillMaxWidth().height(37.dp)
            .background(if (isActive) active else paper)
            .clickable(onClick = onActivate).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, color = if (isActive) onActive else accent, fontSize = 17.sp)
            Spacer(Modifier.width(7.dp))
            Text(title, Modifier.widthIn(max = 120.dp), color = if (isActive) onActive else ink,
                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f).horizontalScroll(pathScroll),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                path.forEachIndexed { index, crumb ->
                    if (index > 0) Text(" / ", color = muted, fontSize = 11.sp)
                    Text(crumb.label, color = if (isActive) onActiveDetail else muted,
                        fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.clickable { onNavigate(crumb.depth) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(23.dp).border(BorderStroke(0.5.dp, divider)),
            verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(10.dp))
            ColumnLabel("NAME", Modifier.weight(1f), sort, SortKey.NAME, onSort)
            ColumnLabel("SIZE", Modifier.width(60.dp), sort, SortKey.SIZE, onSort, TextAlign.End)
            ColumnLabel("DATE", Modifier.width(72.dp), sort, SortKey.DATE, onSort, TextAlign.End)
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(entries, key = { it.id }) { item ->
                val isPicked = pickEnabled && item.id in picked
                // While picking, a tap ticks a file and opens a folder; a hold ticks either.
                Row(Modifier.fillMaxWidth().height(40.dp)
                    .background(if (isPicked) selected else paper)
                    .combinedClickable(
                        onLongClick = if (pickEnabled) ({ onPick(listOf(item)) }) else null,
                        onClick = {
                            if (pickEnabled && !item.directory) onPick(listOf(item)) else onOpen(item)
                        }), verticalAlignment = Alignment.CenterVertically) {
                    if (pickEnabled) PickBox(isPicked) { onPick(listOf(item)) }
                    ItemIcon(item, Modifier.width(34.dp))
                    Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (item.directory) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp, color = ink)
                    Text(if (item.directory || item.size < 0) "" else Formatter.formatShortFileSize(context, item.size),
                        Modifier.width(60.dp).padding(end = 4.dp), textAlign = TextAlign.End,
                        maxLines = 1, fontSize = 10.sp, color = muted)
                    Text(if (item.modified > 0) SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(
                        if (item.modified < 10_000_000_000L) item.modified * 1000 else item.modified
                    )) else "", Modifier.width(72.dp).padding(end = 10.dp),
                        textAlign = TextAlign.End, maxLines = 1, fontSize = 10.sp, color = muted)
                }
                Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(divider.copy(alpha = 0.5f)))
            }
            if (entries.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isBusy) CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
                    Text(emptyMessage, color = muted, fontSize = 12.sp)
                    Text(actionLabel.uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        modifier = Modifier.border(1.dp, divider).clickable(onClick = onAction)
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        if (progress != null) LinearProgressIndicator(progress = { progress },
            Modifier.fillMaxWidth().height(4.dp), color = accent, trackColor = divider)
        Row(Modifier.fillMaxWidth().height(27.dp).border(BorderStroke(0.5.dp, divider))
            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${entries.size} items", fontSize = 11.sp, color = muted)
            Spacer(Modifier.weight(1f))
            // Ticks every file here, or unticks them when all are ticked already.
            val files = entries.filter { !it.directory }
            if (pickEnabled && files.isNotEmpty()) Text(
                if (files.all { it.id in picked }) "UNTICK ALL" else "TICK ALL FILES",
                Modifier.clickable { onPick(files) }.padding(vertical = 4.dp),
                fontSize = 10.sp, color = accent, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
private fun ColumnLabel(label: String, modifier: Modifier, sort: Sort, column: SortKey, onSort: (SortKey) -> Unit,
    alignment: TextAlign = TextAlign.Start) {
    val current = sort.key == column
    Text(label + if (current) if (sort.descending) " ▼" else " ▲" else "",
        modifier.fillMaxHeight().clickable { onSort(column) }.wrapContentHeight()
            .padding(end = if (alignment == TextAlign.End) 10.dp else 0.dp),
        fontSize = 9.sp, letterSpacing = 1.sp, color = if (current) accent else muted, textAlign = alignment,
        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
}
