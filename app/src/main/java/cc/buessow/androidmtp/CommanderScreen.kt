package cc.buessow.androidmtp

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val actionLabel: String, val navigationKey: String
)

private val paper = Color(0xFFF5F0E8)
private val ink = Color(0xFF282725)
private val muted = Color(0xFF77716A)
private val divider = Color(0xFFD9D0C6)
private val accent = Color(0xFFA75030)
private val active = Color(0xFF302923)
private val selected = Color(0xFFF0DCC9)
private val heading = FontFamily.SansSerif

@Composable
internal fun CommanderScreen(
    panes: List<CommanderPane>, activePane: Int, onActivate: (Int) -> Unit,
    onOpen: (Int, CommanderItem) -> Unit,
    onNavigate: (Int, Int) -> Unit, onAction: (Int) -> Unit, onRefresh: () -> Unit,
    showPhoneAccessExplanation: Boolean, onConfirmPhoneAccess: () -> Unit,
    onDismissPhoneAccess: () -> Unit
) {
    // Selection is local decoration only. It never authorizes an operation.
    if (showPhoneAccessExplanation) {
        AlertDialog(
            onDismissRequest = onDismissPhoneAccess,
            title = { Text("Browse phone storage") },
            text = { Text("To show files in the phone's shared storage without choosing folders one by one, MTP Commander needs all-files access. In Settings, enable ‘Allow access to manage all files’ for this app. Browsing is read-only; you can continue using an MTP device if you decline.") },
            confirmButton = { TextButton(onClick = onConfirmPhoneAccess) { Text("Open Settings") } },
            dismissButton = { TextButton(onClick = onDismissPhoneAccess) { Text("Not now") } }
        )
    }
    Column(Modifier.fillMaxSize().background(paper).safeDrawingPadding()) {
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
            listOf("Copy" to "▣", "Rename" to "✎", "Delete" to "×", "MkDir" to "+", "Select" to "☑")
                .forEachIndexed { index, (label, icon) ->
                    Column(Modifier.weight(1f).height(56.dp)
                        .then(if (index < 4) Modifier.border(BorderStroke(0.5.dp, divider)) else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(icon, fontSize = 19.sp, color = muted)
                        Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp, color = muted)
                    }
                }
        }
        key(0, panes[0].navigationKey) {
            Pane(
                title = panes[0].title, symbol = "▯", path = panes[0].path,
                entries = panes[0].entries, isActive = activePane == 0, isBusy = panes[0].busy,
                emptyMessage = panes[0].status,
                actionLabel = panes[0].actionLabel, onAction = { onAction(0) },
                onNavigate = { depth -> onActivate(0); onNavigate(0, depth) },
                onOpen = { item -> if (item.directory) onOpen(0, item) else onActivate(0) },
                onActivate = { onActivate(0) }, modifier = Modifier.weight(1f)
            )
        }
        Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(divider))
            Text(if (activePane == 0) "↓  LEFT → RIGHT  ⇅" else "↑  RIGHT → LEFT  ⇅",
                Modifier.background(paper).border(1.dp, divider)
                    .clickable { onActivate(1 - activePane) }.padding(horizontal = 10.dp, vertical = 4.dp),
                color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
        key(1, panes[1].navigationKey) {
            Pane(
                title = panes[1].title, symbol = "◉", path = panes[1].path,
                entries = panes[1].entries, isActive = activePane == 1, isBusy = panes[1].busy,
                emptyMessage = panes[1].status,
                actionLabel = panes[1].actionLabel, onAction = { onAction(1) },
                onNavigate = { depth -> onActivate(1); onNavigate(1, depth) },
                onOpen = { item -> if (item.directory) onOpen(1, item) else onActivate(1) },
                onActivate = { onActivate(1) }, modifier = Modifier.weight(1f)
            )
        }
        Text("READ ONLY · ${panes[activePane].status}",
            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp, vertical = 9.dp),
            color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Pane(
    title: String, symbol: String, path: List<CommanderCrumb>, entries: List<CommanderItem>,
    isActive: Boolean, isBusy: Boolean, emptyMessage: String,
    actionLabel: String, onAction: () -> Unit, onNavigate: (Int) -> Unit,
    onOpen: (CommanderItem) -> Unit, onActivate: () -> Unit, modifier: Modifier
) {
    val context = LocalContext.current
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var cursor by remember { mutableStateOf<String?>(null) }
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
            Text(title, color = if (isActive) paper else ink, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f).horizontalScroll(pathScroll),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                path.forEachIndexed { index, crumb ->
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
                Row(Modifier.fillMaxWidth().height(40.dp)
                    .background(if (isActive && cursor == item.id) selected else if (cursor == item.id) Color(0xFFEAE2D9) else paper)
                    .clickable { cursor = item.id; onOpen(item) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(34.dp).height(40.dp).clickable {
                        onActivate(); selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
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
            Text("READ ONLY", fontSize = 10.sp, color = muted, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
private fun ColumnLabel(label: String, modifier: Modifier, alignment: TextAlign = TextAlign.Start) {
    Text(label, modifier.padding(end = if (alignment == TextAlign.End) 10.dp else 0.dp),
        fontSize = 9.sp, letterSpacing = 1.sp, color = muted, textAlign = alignment)
}
