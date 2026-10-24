package cc.buessow.mtpcommander

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val selectedIds: Set<String> = emptySet(), val cursor: String? = null, val draggable: Boolean = false
)

internal enum class CommanderMode { NONE, COPY, DELETE }

private data class Drag(val from: Int, val items: List<CommanderItem>, val position: Offset)

private val paper = Color(0xFFF5F0E8)
private val ink = Color(0xFF2C455D)
private val muted = Color(0xFF77716A)
private val divider = Color(0xFFD9D0C6)
private val accent = Color(0xFFA75030)
private val active = Color(0xFF2C455D)
private val selected = Color(0xFFF0DCC9)
private val heading = FontFamily.SansSerif

@Composable
internal fun CommanderScreen(
    panes: List<CommanderPane>, activePane: Int, onActivate: (Int) -> Unit,
    onOpen: (Int, CommanderItem) -> Unit,
    onNavigate: (Int, Int) -> Unit, onAction: (Int) -> Unit, onRefresh: () -> Unit,
    showPhoneAccessExplanation: Boolean, onConfirmPhoneAccess: () -> Unit,
    onDismissPhoneAccess: () -> Unit, mode: CommanderMode, onMode: (CommanderMode) -> Unit,
    trashPane: Int, trash: List<CommanderItem>, onDrop: (Int, Int, List<CommanderItem>) -> Unit,
    onRemoveFromTrash: (String) -> Unit, onDelete: () -> Unit,
    onToggle: (Int, String) -> Unit, onSelectAll: () -> Unit, onCursor: (Int, String) -> Unit
) {
    val density = LocalDensity.current
    val paneBounds = remember { mutableStateListOf(Rect.Zero, Rect.Zero) }
    val buttonBounds = remember { mutableStateListOf(Rect.Zero, Rect.Zero, Rect.Zero) }
    var drag by remember { mutableStateOf<Drag?>(null) }
    var balloonHidden by remember(mode) { mutableStateOf(false) }
    fun dropTarget(current: Drag): Int? = paneBounds.indexOfFirst { it.contains(current.position) }
        .takeIf { it >= 0 && it != current.from && (mode == CommanderMode.COPY || it == trashPane) }
    if (showPhoneAccessExplanation) {
        AlertDialog(
            onDismissRequest = onDismissPhoneAccess,
            title = { Text("Browse phone storage") },
            text = { Text("To show files in the phone's shared storage without choosing folders one by one, MTP Commander needs all-files access. In Settings, enable ‘Allow access to manage all files’ for this app. Browsing is read-only; you can continue using an MTP device if you decline.") },
            confirmButton = { TextButton(onClick = onConfirmPhoneAccess) { Text("Open Settings") } },
            dismissButton = { TextButton(onClick = onDismissPhoneAccess) { Text("Not now") } }
        )
    }
    Box(Modifier.fillMaxSize().background(paper)) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("MTP COMMANDER", fontFamily = heading, fontWeight = FontWeight.Black,
                fontSize = 23.sp, letterSpacing = (-0.7).sp, color = ink, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(panes[activePane].title, color = accent, fontSize = 10.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.width(106.dp),
                textAlign = TextAlign.End)
            Text(" ↻ ", modifier = Modifier.clickable(onClick = onRefresh).padding(5.dp),
                fontSize = 22.sp, color = accent)
        }
        Row(Modifier.fillMaxWidth().border(BorderStroke(0.5.dp, divider))) {
            listOf(Triple("Copy", "▣", CommanderMode.COPY), Triple("Delete", "×", CommanderMode.DELETE),
                Triple("Select", "☑", null)).forEachIndexed { index, (label, icon, toggle) ->
                val on = toggle != null && mode == toggle
                Column(Modifier.weight(1f).height(56.dp)
                    .onGloballyPositioned { buttonBounds[index] = it.boundsInRoot() }
                    .background(if (on) active else paper)
                    .clickable { if (toggle == null) onSelectAll() else onMode(toggle) }
                    .border(BorderStroke(0.5.dp, divider)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(icon, fontSize = 19.sp, color = if (on) paper else muted)
                    Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp, color = if (on) paper else muted)
                }
            }
        }
        panes.forEachIndexed { index, pane ->
            if (index == 1) Spacer(Modifier.fillMaxWidth().height(1.dp).background(divider))
            val target = drag?.let(::dropTarget) == index
            val paneModifier = Modifier.weight(1f)
                .onGloballyPositioned { paneBounds[index] = it.boundsInRoot() }
                .then(if (target) Modifier.border(2.dp, accent) else Modifier)
            if (mode == CommanderMode.DELETE && index == trashPane)
                Trash(trash, onRemove = onRemoveFromTrash, onDelete = onDelete, modifier = paneModifier)
            else key(index, pane.navigationKey) {
                Pane(
                    title = pane.title, symbol = if (index == 0) "▯" else "◉", path = pane.path,
                    entries = pane.entries, isActive = activePane == index, isBusy = pane.busy,
                    selectedIds = pane.selectedIds, cursor = pane.cursor,
                    onToggle = { onToggle(index, it) }, onCursor = { onCursor(index, it) },
                    emptyMessage = pane.status,
                    actionLabel = pane.actionLabel, onAction = { onAction(index) },
                    onNavigate = { depth -> onActivate(index); onNavigate(index, depth) },
                    onOpen = { item -> if (item.directory) onOpen(index, item) else onActivate(index) },
                    onActivate = { onActivate(index) },
                    hint = when (mode) {
                        CommanderMode.COPY -> "LONG-PRESS & DRAG TO COPY"
                        CommanderMode.DELETE -> "LONG-PRESS & DRAG TO TRASH"
                        CommanderMode.NONE -> ""
                    },
                    dragEnabled = mode != CommanderMode.NONE && pane.draggable && !pane.busy,
                    onDragStart = { items, position ->
                        onActivate(index); balloonHidden = true; drag = Drag(index, items, position)
                    },
                    onDragBy = { amount -> drag = drag?.let { it.copy(position = it.position + amount) } },
                    onDragEnd = {
                        drag?.let { current -> dropTarget(current)?.let { onDrop(current.from, it, current.items) } }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                    modifier = paneModifier
                )
            }
        }
        Text(panes[activePane].status,
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp, vertical = 5.dp),
            color = muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val button = buttonBounds[if (mode == CommanderMode.DELETE) 1 else 0]
    if (mode != CommanderMode.NONE && !balloonHidden && button != Rect.Zero) {
        val message = if (mode == CommanderMode.COPY)
            "Long-press a file and drag it onto the other pane to copy it there. If the file is marked, " +
                "all marked files are copied. Tap COPY again when done."
        else "Long-press files or folders and drag them into the trash. Nothing is deleted until you " +
            "press DELETE in the trash; tap an item there to take it out again."
        Column(Modifier.offset { IntOffset(0, button.bottom.toInt()) }.fillMaxWidth()
            .clickable { balloonHidden = true }) {
            // Outlined in paper so the balloon stands out on the dark pane and trash headers it overlaps.
            Canvas(Modifier.offset { IntOffset(button.center.x.toInt() - with(density) { 9.dp.roundToPx() }, 0) }
                .size(18.dp, 11.dp).zIndex(1f)) {
                val arrow = Path().apply {
                    moveTo(size.width / 2, 0f); lineTo(size.width, size.height + 2.dp.toPx())
                    lineTo(0f, size.height + 2.dp.toPx()); close()
                }
                drawPath(arrow, ink)
                drawPath(arrow, paper, style = Stroke(2.dp.toPx()))
            }
            Column(Modifier.offset(y = (-2).dp).padding(horizontal = 12.dp).fillMaxWidth()
                .background(ink, RoundedCornerShape(8.dp)).border(2.dp, paper, RoundedCornerShape(8.dp))
                .padding(12.dp)) {
                Text(message, color = paper, fontSize = 13.sp)
                Text("TAP TO HIDE", color = Color(0xFFE6DBD0), fontSize = 9.sp, letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    drag?.let { current ->
        Text(if (current.items.size == 1) "▤ ${current.items.single().name}" else "▤ ${current.items.size} items",
            Modifier.offset { IntOffset(current.position.x.toInt() - with(density) { 24.dp.roundToPx() },
                current.position.y.toInt() - with(density) { 44.dp.roundToPx() }) }
                .background(accent, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
                .widthIn(max = 220.dp),
            color = paper, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    }
}

@Composable
private fun Trash(entries: List<CommanderItem>, onRemove: (String) -> Unit, onDelete: () -> Unit, modifier: Modifier) {
    Column(modifier.background(paper)) {
        Row(Modifier.fillMaxWidth().height(37.dp).background(accent).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("🗑", fontSize = 17.sp)
            Spacer(Modifier.width(7.dp))
            Text("TRASH", color = paper, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            Text("${entries.size} item${if (entries.size == 1) "" else "s"}", color = paper, fontSize = 11.sp)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().height(40.dp).clickable { onRemove(item.id) }
                    .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item.directory) "▱" else "▤", color = if (item.directory) accent else ink, fontSize = 17.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp, color = ink)
                    Text("✕", color = muted, fontSize = 14.sp)
                }
                Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(divider.copy(alpha = 0.5f)))
            }
            if (entries.isEmpty()) item {
                Text("Drag files and folders here. They are only deleted when you press DELETE.",
                    Modifier.padding(18.dp), color = muted, fontSize = 12.sp)
            }
        }
        Text("DELETE ${entries.size} ITEM${if (entries.size == 1) "" else "S"}",
            Modifier.fillMaxWidth().height(44.dp)
                .background(if (entries.isEmpty()) divider else accent)
                .clickable(enabled = entries.isNotEmpty(), onClick = onDelete)
                .padding(vertical = 13.dp),
            color = paper, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun Pane(
    title: String, symbol: String, path: List<CommanderCrumb>, entries: List<CommanderItem>,
    isActive: Boolean, isBusy: Boolean, emptyMessage: String,
    selectedIds: Set<String>, cursor: String?, onToggle: (String) -> Unit, onCursor: (String) -> Unit,
    actionLabel: String, onAction: () -> Unit, onNavigate: (Int) -> Unit,
    onOpen: (CommanderItem) -> Unit, onActivate: () -> Unit, hint: String, dragEnabled: Boolean,
    onDragStart: (List<CommanderItem>, Offset) -> Unit, onDragBy: (Offset) -> Unit,
    onDragEnd: () -> Unit, onDragCancel: () -> Unit, modifier: Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val pathScroll = rememberScrollState()
    LaunchedEffect(path, pathScroll.maxValue) { pathScroll.scrollTo(pathScroll.maxValue) }
    Column(modifier.background(paper)) {
        Row(Modifier.fillMaxWidth().height(37.dp)
            .background(if (isActive) active else paper)
            .clickable(onClick = onActivate).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, color = if (isActive) paper else accent, fontSize = 17.sp)
            Spacer(Modifier.width(7.dp))
            Text(title, Modifier.widthIn(max = 86.dp), color = if (isActive) paper else ink,
                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.width(8.dp))
            path.firstOrNull()?.let { root ->
                Text(root.label, color = if (isActive) Color(0xFFE6DBD0) else muted,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 72.dp).clickable { onNavigate(root.depth) })
            }
            if (path.size > 1) Text(" / ", color = muted, fontSize = 11.sp)
            Row(Modifier.weight(1f).horizontalScroll(pathScroll),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                path.drop(1).forEachIndexed { index, crumb ->
                    if (index > 0) Text(" / ", color = muted, fontSize = 11.sp)
                    Text(crumb.label, color = if (isActive) Color(0xFFE6DBD0) else muted,
                        fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.clickable { onNavigate(crumb.depth) })
                }
            }
            Text(" ⋯ ", color = if (isActive) paper else accent,
                modifier = Modifier.clickable(onClick = onAction), fontSize = 20.sp)
        }
        Row(Modifier.fillMaxWidth().height(23.dp).border(BorderStroke(0.5.dp, divider)),
            verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(34.dp))
            ColumnLabel("NAME", Modifier.weight(1f))
            ColumnLabel("SIZE", Modifier.width(60.dp), TextAlign.End)
            ColumnLabel("DATE", Modifier.width(72.dp), TextAlign.End)
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(entries, key = { it.id }) { item ->
                val marked = item.id in selectedIds
                var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                Row(Modifier.fillMaxWidth().height(40.dp)
                    .onGloballyPositioned { coordinates = it }
                    .then(if (dragEnabled) Modifier.pointerInput(item.id, selectedIds) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val dragged = if (item.id in selectedIds) entries.filter { it.id in selectedIds }
                                    else listOf(item)
                                onDragStart(dragged, coordinates?.localToRoot(offset) ?: offset)
                            },
                            onDrag = { change, amount -> change.consume(); onDragBy(amount) },
                            onDragEnd = onDragEnd, onDragCancel = onDragCancel)
                    } else Modifier)
                    .background(if (isActive && cursor == item.id) selected else if (cursor == item.id) Color(0xFFEAE2D9) else paper)
                    .clickable { onCursor(item.id); onOpen(item) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(34.dp).height(40.dp).clickable {
                        onActivate(); onToggle(item.id)
                    },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(15.dp).background(if (marked) accent else paper)
                            .border(1.dp, if (marked) accent else muted), contentAlignment = Alignment.Center) {
                            if (marked) Text("✓", color = paper, fontSize = 11.sp, lineHeight = 11.sp)
                        }
                    }
                    Text(if (item.directory) "▱" else "▤", color = if (item.directory) accent else ink, fontSize = 17.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (item.directory) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp, color = if (marked) accent else ink)
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
        Row(Modifier.fillMaxWidth().height(27.dp).border(BorderStroke(0.5.dp, divider))
            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectedIds.isEmpty()) "${entries.size} items" else "${selectedIds.size} marked",
                fontSize = 11.sp, color = if (selectedIds.isEmpty()) muted else accent)
            Spacer(Modifier.weight(1f))
            Text(hint, fontSize = 10.sp, color = accent, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
private fun ColumnLabel(label: String, modifier: Modifier, alignment: TextAlign = TextAlign.Start) {
    Text(label, modifier.padding(end = if (alignment == TextAlign.End) 10.dp else 0.dp),
        fontSize = 9.sp, letterSpacing = 1.sp, color = muted, textAlign = alignment)
}
