package fi.palonkorpi.sidehome.pane

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.palonkorpi.sidehome.ui.Inter
import fi.palonkorpi.sidehome.ui.LineIcon

/**
 * **The Notes page** — the left-hand page when the user picks it. A short list you keep on the
 * sky: a line of journal, a reminder. Tap a line to tick it off (it stays, struck through, as a
 * record); hold a line to remove it; "Add a note" opens a composer that replaces the page, the
 * way the weather's city composer does, and Enter commits.
 *
 * Deliberately no alarms, no times, no notifications: a reminder here is a line you will see the
 * next time you swipe to it. That is the whole feature, and it needs no permission.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesPane(
    fg: Color,
    accent: Color,
    onComposerOpenChange: (Boolean) -> Unit = {},
    store: NotesStore = rememberNotesStore(),
    // Owned by the host: the gesture arbiter routes a vertical drag on this pane to it.
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    val notes by store.notes.collectAsState()
    var composerOpen by remember { mutableStateOf(false) }

    DisposableEffect(composerOpen) {
        onComposerOpenChange(composerOpen)
        onDispose { if (composerOpen) onComposerOpenChange(false) }
    }

    if (composerOpen) {
        NoteComposer(
            fg = fg,
            accent = accent,
            onCommit = { store.add(it); composerOpen = false },
            onDismiss = { composerOpen = false },
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState, enabled = false)
                .heightIn(min = maxHeight)
                .padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Notes", color = fg, fontFamily = Inter, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(14.dp))
            notes.forEach { note ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { store.toggle(note.id) },
                            onLongClick = { store.remove(note.id) },
                        )
                        .padding(vertical = 9.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A ticked line wears the accent check; an open one a quiet ring.
                    if (note.done) {
                        LineIcon(glyph = "check", color = accent, size = 18.dp, strokeWidthUnits = 1.7f)
                    } else {
                        Box(Modifier.size(18.dp).padding(2.dp).border(1.5.dp, fg.copy(alpha = 0.7f), CircleShape))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        note.text,
                        color = fg.copy(alpha = if (note.done) 0.5f else 1f),
                        fontFamily = Inter,
                        fontSize = 18.sp,
                        textDecoration = if (note.done) TextDecoration.LineThrough else null,
                    )
                }
            }
            if (notes.isNotEmpty()) Spacer(Modifier.height(8.dp))
            Text(
                if (notes.isEmpty()) "Add a note" else "Add",
                color = accent,
                fontFamily = Inter,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { composerOpen = true }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun NoteComposer(
    fg: Color,
    accent: Color,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val rule = fg.copy(alpha = 0.35f)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(enabled = true, onBack = onDismiss)

    // The panes draw edge-to-edge, so a top-anchored composer insets itself under the status bar.
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 32.dp, end = 28.dp, top = 24.dp, bottom = 44.dp),
    ) {
        Text("A note", color = fg, fontFamily = Inter, fontSize = 22.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(14.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = fg, fontSize = 18.sp, fontFamily = Inter),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onCommit(text) }),
            modifier = Modifier
                .focusRequester(focus)
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 2.dp)
                .drawBehind {
                    val y = size.height
                    drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                },
            decorationBox = { innerTextField ->
                Box {
                    if (text.isEmpty()) {
                        Text("Something to remember", color = fg.copy(alpha = 0.6f), fontFamily = Inter, fontSize = 18.sp)
                    }
                    innerTextField()
                }
            },
        )
        Spacer(Modifier.height(18.dp))
        Row {
            Text(
                "Cancel",
                color = accent,
                fontFamily = Inter,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onDismiss() }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
            )
            Spacer(Modifier.width(24.dp))
            if (text.isNotBlank()) {
                Text(
                    "Keep",
                    color = accent,
                    fontFamily = Inter,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCommit(text) }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                )
            }
        }
    }
}
