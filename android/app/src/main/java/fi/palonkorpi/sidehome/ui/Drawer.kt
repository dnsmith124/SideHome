package fi.palonkorpi.sidehome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.palonkorpi.sidehome.apps.Category
import fi.palonkorpi.sidehome.apps.AppEntry
import fi.palonkorpi.sidehome.apps.FolderItem
import fi.palonkorpi.sidehome.apps.Shelf
import fi.palonkorpi.sidehome.gesture.GestureTuning

private sealed interface DrawerItem
private data class SectionHeader(val letter: String) : DrawerItem
private data class AppRow(val app: AppEntry) : DrawerItem

/**
 * The app drawer / spotlight. Frameless: it never dims or tints the sky — it floats
 * on top while the home recedes beneath. Alphabetical with quiet section letters.
 *
 * Search is a real focusable [BasicTextField] wired to the active IME,
 * so it types correctly through the active IME instead of reading raw key codes. The on-brand
 * look (search glyph + accent caret) is kept via a custom `decorationBox`; the field just holds
 * the query.
 *
 * With [searchFirst] the drawer opens "already listening": the field takes focus and the keyboard
 * rises, so the top hits are a few letters away. Otherwise it opens unfocused ("Tap to search") so
 * the keyboard never covers the list on open. The IME action is Search, so Enter launches the top
 * hit; Backspace edits; swipe-down (from the top) or Back dismisses.
 *
 * Holding an app raises the §1 menu ([onAppHold]); moving after that hold lifts the app out of the
 * library and streams it to the host ([onAppPickup] / [onAppDrag] / [onAppDrop]) — the drag-up that
 * carries it onto the home (SID-30). The drawer does not melt itself: the host is what animates it
 * away, and what must keep it composed until the carry lands, or the finger holding the app is
 * cancelled with it.
 *
 * [mode] picks the **browse** state only (SID-23, SPEC §2b): the alphabetical list, or one of the two
 * category-bubble variants. Search overrides it wholesale — a non-empty query always collapses to the
 * flat ranked list, whatever the mode, because ranked results have an order of their own and grouping
 * them would throw it away. It is hoisted rather than owned here for a blunt reason: this drawer
 * remounts on every open, so a mode it held itself would reset to the default every summon, and the
 * side-by-side comparison the mode exists for would be impossible to run.
 *
 * @param mode prototype state, deliberately not persisted: §2b expects to *pick* a winner and delete
 *   the losers, so a [fi.palonkorpi.sidehome.settings.SettingsStore] entry would be built to be thrown away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Drawer(
    apps: List<AppEntry>,
    favouriteKeys: Set<String>,
    fg: Color,
    accent: Color,
    visible: Boolean,
    atRest: Boolean,
    tuning: GestureTuning,
    mode: BrowseMode,
    onModeChange: (BrowseMode) -> Unit,
    // Which Gallery bucket is drilled into, hoisted to the host (SID-96) so its Back ladder can step
    // out of the bucket before closing the library. Null = the bubble grid. See [galleryBucket] in the
    // host. Defaults keep the drawer usable in isolation (previews/tests).
    openCategory: Category? = null,
    onOpenCategoryChange: (Category?) -> Unit = {},
    // The Hidden and Locked shelves (Pixel Edition). [apps] is the visible library; these two are
    // what sits at its foot. Which shelf is open is hoisted like [openCategory], so Back steps out
    // of it first — and because opening Locked is the host's decision (it asks for the device
    // credential), the drawer only *requests* a shelf and the host flips [openShelf].
    hidden: List<AppEntry> = emptyList(),
    locked: List<AppEntry> = emptyList(),
    openShelf: Shelf? = null,
    onOpenShelfChange: (Shelf?) -> Unit = {},
    onRequestShelf: (Shelf) -> Unit = {},
    onShelfHold: (AppEntry, Shelf, Rect) -> Unit = { _, _, _ -> },
    // Multi-select (Pixel Edition): the marked keys, or null when not selecting. Hoisted so Back can
    // end it. While it is on, the flat list shows (the bubbles step aside), a tap marks instead of
    // launching, and a bar at the foot shelves the set.
    selection: Set<String>? = null,
    onSelectionChange: (Set<String>?) -> Unit = {},
    onShelveSelection: (Shelf) -> Unit = {},
    // The selection bar's fill: the host's opaque panel colour (the strip's), so rows never show
    // through it. Defaults to a translucent wash for previews.
    barColor: Color = fg.copy(alpha = 0.07f),
    onLaunch: (AppEntry) -> Unit,
    // SID-182: the list-first pref, inverted — false opens the A–Z list quiet (no focus, no IME);
    // true opens it with the search field focused and the keyboard up.
    searchFirst: Boolean = false,
    onAppHold: (AppEntry, Rect) -> Unit,
    onAppPickup: (AppEntry, Rect) -> Unit,
    onAppDrag: (Offset) -> Unit,
    onAppDrop: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val gridScroll = rememberScrollState()

    // SID-79: pull-down-from-anywhere to dismiss. The `detectVerticalDragGestures` on the drawer
    // column below only ever sees drags over the non-scrolling header (the grabber), because the list
    // consumes vertical drags first — which is why closing felt like it required grabbing the top. A
    // downward pull *over the list* shows up here instead: once the list (or the grouped grid) is at
    // the top it can't consume any more, so its leftover downward delta arrives in [onPostScroll].
    // Accumulate that and close past a threshold, swallowing it so the list shows no overscroll glow.
    // No explicit "at top" check is needed: away from the top the list consumes the drag, so nothing
    // is left over here.
    val currentDismiss by rememberUpdatedState(onDismiss)
    val dismissPullPx = with(LocalDensity.current) { 88.dp.toPx() }
    val pullToDismiss = remember(dismissPullPx) {
        object : NestedScrollConnection {
            private var pulled = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f) pulled = 0f // scrolling back up cancels an in-progress pull
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pulled += available.y
                if (pulled >= dismissPullPx) { pulled = 0f; currentDismiss() }
                return Offset(0f, available.y) // swallow the at-top overscroll
            }
        }
    }


    // Notification dots (SID-46). Read from the process-wide badge source rather than passed down:
    // a dot is a property of the *package*, not of this screen — see NotificationDot.kt.
    val badgedPackages = rememberBadgedPackages()

    // Search always collapses to the flat ranked list, whatever the browse mode (SPEC §2b). Computed
    // up here (not just before the list) because the auto-focus decision turns on which surface — the
    // A–Z list or the grouped gallery — is showing.
    val searching = query.trim().isNotEmpty()
    val selecting = selection != null
    val grouped = mode != BrowseMode.LIST && !searching && !selecting

    // Auto-focus is a typing policy (SID-182): search-first opens the A–Z List already listening;
    // the App Gallery is browse-y, so its field stays unfocused either way.
    val autoFocus = searchFirst && !grouped

    // Focus — and therefore the keyboard — only once the pane is actually open, not the instant a
    // drag reveals the warmed drawer: an aborted open (drag up a little, let it fall back) must not
    // leave a keyboard on screen with no field behind it. Keyed on [visible] (drawer-full) and
    // [autoFocus]: a partial open never focuses; a real close clears it; and flipping to the Gallery
    // mid-open releases focus.
    LaunchedEffect(visible, autoFocus) {
        if (visible && autoFocus) {
            runCatching { focus.requestFocus() }
        } else {
            // clearFocus tears down the field's InputConnection; hide() dismisses the keyboard now
            // rather than leaving it to linger over the home once the drawer closes.
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    // Reset for the next summon once the drawer is fully at rest and off-screen. The pane is warmed
    // once and no longer remounts per open, so nothing else clears the query / scroll / open bucket;
    // doing it while hidden means a reopen always lands clean (empty query, list at top, bubbles not a
    // drilled-in bucket) with no visible flash. atRest covers an aborted partial open too.
    LaunchedEffect(atRest) {
        if (atRest) {
            query = ""
            onOpenCategoryChange(null)
            onOpenShelfChange(null)
            onSelectionChange(null)
            listState.scrollToItem(0)
            runCatching { gridScroll.scrollTo(0) }
        }
    }

    val ranked = remember(apps, query) { rankApps(apps, query) }
    val items = remember(ranked, query) { toItems(ranked, query) }
    val hint = fg.copy(alpha = 0.22f)

    val buckets = remember(apps) { bucketApps(apps) }
    val appsByKey = remember(apps) { apps.associateBy { it.key } }
    // An open shelf whose last app was unshelved has nothing left to show: step back to the list.
    LaunchedEffect(openShelf, hidden.size, locked.size) {
        if ((openShelf == Shelf.HIDDEN && hidden.isEmpty()) || (openShelf == Shelf.LOCKED && locked.isEmpty())) {
            onOpenShelfChange(null)
        }
    }
    // Which bucket's popover is open, matched on id (not the whole [Category]): a bucket's label/glyph
    // can be edited (user buckets) or its members change while it is open, and only the id is stable.
    // Gated on `grouped` in composition, not just cleared by the effect below, so flipping to List with
    // a bucket open can't paint the popover once over the list before the state catches up — and it
    // covers the bucket whose category no longer exists (every member re-categorised out, or folded
    // into Other under MIN_BUCKET_SIZE), which resolves to null and drops back to the bubbles.
    val openBucket = if (!grouped) null else buckets.firstOrNull { it.category.id == openCategory?.id }

    // Leaving grouped mode — the toggle flipped to List, or a keystroke turned this into a search —
    // forgets the open bucket too, so coming back lands on the bubbles rather than re-opening what
    // you left. The gate above is what makes it disappear; this is what stops it returning.
    LaunchedEffect(grouped) { if (!grouped) onOpenCategoryChange(null) }

    // Back closes the bucket before the drawer. Registered inside the drawer and enabled only while a
    // bucket is open, so it wins over the host's always-enabled handler (registered earlier) exactly
    // then, and the host's chain — which cannot see this state — is left alone otherwise.
    BackHandler(enabled = openCategory != null) { onOpenCategoryChange(null) }

    // The swipe-down dismissal reads this from inside a pointerInput that never restarts, so it would
    // otherwise capture the mode the drawer opened in and never see a flip.
    val groupedNow by rememberUpdatedState(grouped)

    Box(modifier = modifier) {
        // An open bucket replaces the drawer's own content rather than floating over it: DECISIONS
        // says stacked overlays don't bleed — you only ever read one surface — and here the surface
        // underneath is a grid of bubbles the popover is the drill-down *into*, so leaving it showing
        // would be the loudest possible version of that bleed. The drawer itself stays open behind
        // the swap; dismissing the bucket returns to the bubbles.
        if (openBucket != null) {
            CategoryPopover(
                bucket = openBucket,
                appsByKey = appsByKey,
                tuning = tuning,
                fg = fg,
                accent = accent,
                onLaunch = onLaunch,
                onAppHold = onAppHold,
                onDismiss = { onOpenCategoryChange(null) },
            )
        } else if (openShelf != null) {
            // A shelf opens in place of the list, exactly as a bucket does (Pixel Edition).
            ShelfPopover(
                shelf = openShelf,
                apps = if (openShelf == Shelf.HIDDEN) hidden else locked,
                tuning = tuning,
                fg = fg,
                accent = accent,
                onLaunch = onLaunch,
                onHold = { app, bounds -> onShelfHold(app, openShelf, bounds) },
                onDismiss = { onOpenShelfChange(null) },
            )
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Sit the whole drawer above the keyboard: with search-first the field auto-focuses,
                // and without this the last list rows / bottom bubble row hid behind it. imePadding
                // collapses to nothing when the IME is down.
                .imePadding()
                .nestedScroll(pullToDismiss)
                .padding(top = 36.dp)
                .padding(start = 28.dp, end = 18.dp, bottom = 12.dp)
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f },
                        onVerticalDrag = { _, dy -> acc += dy },
                        onDragEnd = {
                            // Read here, inside the gesture's own coroutine, never in composition:
                            // subscribing composition to a live scroll offset would recompose the
                            // whole drawer on every scrolled pixel.
                            val atTop = if (groupedNow) {
                                gridScroll.value == 0
                            } else {
                                listState.firstVisibleItemIndex == 0 &&
                                    listState.firstVisibleItemScrollOffset == 0
                            }
                            if (acc > 90f && atTop) onDismiss()
                        },
                        onDragCancel = { acc = 0f },
                    )
                },
        ) {
            // grabber
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .size(width = 40.dp, height = 5.dp)
                    .drawBehind { drawRect(fg.copy(alpha = 0.32f)) },
            )

            // "search" line: a real IME-backed field, styled by decorationBox to look exactly
            // like the old static query line (search glyph, live text, accent caret). The field
            // holds the query. Live
            // narrowing follows composing text — onValueChange fires on composition, not just on
            // commit — so the list filters as you type, before you accept a candidate.
            val placeholder = if (autoFocus) "Type to search" else "Tap to search"
            // The mode toggle rides the search line (SPEC §2b), so the field takes the remaining
            // width rather than the full row. Field and toggle are matching translucent-fg pills, so
            // the search line reads as one set of controls rather than an underlined field beside a box.
            Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = fg, fontSize = 18.sp, fontFamily = Inter),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isNotBlank()) ranked.firstOrNull()?.let(onLaunch)
                }),
                modifier = Modifier
                    .focusRequester(focus)
                    .weight(1f)
                    // A soft rounded search pill instead of a Material underline, so it reads as a set
                    // with the mode toggle beside it (the same translucent-fg cushion the toggle uses,
                    // per DECISIONS) rather than a bare underlined field next to a boxed control.
                    .clip(RoundedCornerShape(12.dp))
                    .background(fg.copy(alpha = 0.07f))
                    .padding(vertical = 10.dp, horizontal = 12.dp),
                decorationBox = { innerTextField ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineIcon(glyph = "search", color = fg.copy(alpha = 0.85f), size = 22.dp, strokeWidthUnits = 1.7f)
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                // One line, no wrap: on a narrow field "Type to search" used to wrap
                                // to two lines and stretch the whole search row tall. The field is
                                // single-line anyway, so the hint must be too.
                                Text(
                                    placeholder,
                                    color = fg.copy(alpha = 0.6f),
                                    fontSize = 18.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            innerTextField()
                        }
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            // Multi-select (Pixel Edition) lives beside the search, as a pill like the mode toggle:
            // tap to start marking, tap again (or Cancel, or Back) to stop. Accent while it is on.
            SelectToggle(
                selecting = selecting,
                fg = fg,
                accent = accent,
                onToggle = { onSelectionChange(if (selecting) null else emptySet()) },
            )
            Spacer(Modifier.width(8.dp))
            BrowseModeToggle(mode = mode, onModeChange = onModeChange, fg = fg, accent = accent)
            }

            Spacer(Modifier.height(6.dp))

            // Kill the Android-12 overscroll STRETCH: it wraps the whole list in a per-frame
            // RenderEffect layer (expensive on this GPU) and causes the gap-stretch at the edges.
            // Providing null removes both — the list just stops firmly at its ends. It wraps the
            // bubbles too: the grid scrolls, so it would stretch exactly the same way.
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            if (grouped) {
                GroupedBrowse(
                    buckets = buckets,
                    fg = fg,
                    scrollState = gridScroll,
                    onOpen = { onOpenCategoryChange(it.category) },
                    modifier = Modifier.fillMaxSize(),
                    accent = accent,
                    footer = { ShelfRows(hidden = hidden.size, locked = locked.size, fg = fg, onOpen = onRequestShelf) },
                )
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // Room under the last row for the selection bar while it is up.
                contentPadding = PaddingValues(top = 2.dp, bottom = if (selecting) 96.dp else 24.dp),
            ) {
                if (items.isEmpty()) {
                    item {
                        Text(
                            text = "No apps match “${query.trim()}”",
                            color = fg,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                        )
                    }
                }
                // stable keys + contentType let the list reuse row compositions as items fly
                // past instead of rebuilding them each frame — the main lever for fling perf
                items(
                    count = items.size,
                    key = { i ->
                        when (val it = items[i]) {
                            is SectionHeader -> "h_${it.letter}"
                            is AppRow -> it.app.key
                        }
                    },
                    contentType = { i -> items[i] is SectionHeader },
                ) { i ->
                    when (val it = items[i]) {
                        is SectionHeader -> Text(
                            text = it.letter,
                            color = fg,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.14.em,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 15.dp, bottom = 5.dp),
                        )
                        is AppRow -> DrawerAppRow(
                            app = it.app,
                            fg = fg,
                            accent = accent,
                            badged = isBadged(it.app, badgedPackages),
                            isFavourite = favouriteKeys.contains(it.app.key),
                            tuning = tuning,
                            selecting = selecting,
                            selected = selection?.contains(it.app.key) == true,
                            // Selecting, a tap marks the row instead of launching it.
                            onLaunch = {
                                val current = selection
                                if (current == null) onLaunch(it.app)
                                else onSelectionChange(if (it.app.key in current) current - it.app.key else current + it.app.key)
                            },
                            onHold = { bounds -> onAppHold(it.app, bounds) },
                            onPickup = { bounds -> onAppPickup(it.app, bounds) },
                            onDrag = onAppDrag,
                            onDrop = onAppDrop,
                        )
                    }
                }
                // The shelves sit at the foot of the A–Z list (Pixel Edition), never among search
                // results — a search is a question about the library, and a shelf is not an answer.
                if (!searching && !selecting && (hidden.isNotEmpty() || locked.isNotEmpty())) {
                    item(key = "shelves", contentType = "shelves") {
                        ShelfRows(hidden = hidden.size, locked = locked.size, fg = fg, onOpen = onRequestShelf)
                    }
                }
            }
            }
            }
        }
        }
        // The selection bar (Pixel Edition): what the marked set can become, and the way out. Over the
        // list, at the foot of the drawer, clear of the nav bar.
        selection?.let { marked ->
            SelectionBar(
                count = marked.size,
                fg = fg,
                accent = accent,
                barColor = barColor,
                onHide = { onShelveSelection(Shelf.HIDDEN) },
                onLock = { onShelveSelection(Shelf.LOCKED) },
                onCancel = { onSelectionChange(null) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }
}

/** The select button (Pixel Edition): the mode toggle's twin, one cell, lit in accent while on. */
@Composable
private fun SelectToggle(selecting: Boolean, fg: Color, accent: Color, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(fg.copy(alpha = 0.07f))
            .padding(2.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selecting) fg.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onToggle() }
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        LineIcon(
            glyph = "checkcircle",
            color = if (selecting) accent else fg,
            size = 17.dp,
            strokeWidthUnits = 1.6f,
        )
    }
}

/**
 * The multi-select bar (Pixel Edition): a count, Hide and Lock for the marked apps, and Cancel. The
 * verbs dim until something is marked, the same way a strip cell dims when it cannot run.
 */
@Composable
private fun SelectionBar(
    count: Int,
    fg: Color,
    accent: Color,
    barColor: Color,
    onHide: () -> Unit,
    onLock: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(barColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 1) "1 selected" else "$count selected",
            color = fg,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        BarVerb("Cancel", accent, enabled = true, onClick = onCancel)
        Spacer(Modifier.width(18.dp))
        BarVerb("Hide", accent, enabled = count > 0, onClick = onHide)
        Spacer(Modifier.width(18.dp))
        BarVerb("Lock", accent, enabled = count > 0, onClick = onLock)
    }
}

@Composable
private fun BarVerb(label: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = accent.copy(alpha = if (enabled) 1f else 0.35f),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 4.dp, horizontal = 2.dp),
    )
}

/**
 * A category bucket opened from a bubble (SID-23) — the folder-open overlay, reused rather than
 * forked. A bucket *is* a group of apps with a name and a glyph, which is precisely what
 * [FolderPopover] draws, so it arrives here as a synthetic [FolderItem] and inherits the header, the
 * staggered rise, the member dots, and tap-to-dismiss for free.
 *
 * The one thing not inherited is the action strip: holds are delegated to the host via [onAppHold],
 * the same callback the drawer's list rows use, so a held app raises the **library-app** strip (Icon /
 * Home / Category / Info) instead of the folder-member one — see [FolderPopover]'s `onMemberHold` for
 * why the folder's verbs are wrong here. That also means the miscategorisation fix §2b asks for is
 * the same Category cell the list already offers, reached the same way.
 *
 * Not offered here, deliberately: dragging an app out onto the home. [FolderPopover] declines it for
 * folders on the grounds that a member has no slot waiting for it, and the argument carries over
 * unchanged. Hold → Home still works, through the placement flow.
 */
@Composable
private fun CategoryPopover(
    bucket: CategoryBucket,
    appsByKey: Map<String, AppEntry>,
    tuning: GestureTuning,
    fg: Color,
    accent: Color,
    onLaunch: (AppEntry) -> Unit,
    onAppHold: (AppEntry, Rect) -> Unit,
    onDismiss: () -> Unit,
) {
    // Rebuilt only when the bucket changes. FolderItem is a data class, so even an identical rebuild
    // compares equal and doesn't re-key the popover's own remembers.
    val folder = remember(bucket) {
        FolderItem(
            name = bucket.category.label,
            appKeys = bucket.apps.map { it.key },
            glyph = bucket.category.glyph,
        )
    }
    FolderPopover(
        folder = folder,
        appsByKey = appsByKey,
        glyphFor = { it.glyph },
        tuning = tuning,
        fg = fg,
        accent = accent,
        // Only ever the trailing carry chip's background, and a bucket has no reorder, so it is never
        // drawn — a sane value rather than a placeholder, legible if that ever stops being true.
        stripColor = fg.copy(alpha = 0.10f),
        onLaunch = onLaunch,
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxSize(),
        // Every hold delegates to the host, which raises the library-app strip (Icon / Home / Category
        // / Info) — the right verbs for an app seen in the library, not the folder-member ones (SID-114).
        onMemberHold = onAppHold,
        // The closed bubble peeks these apps' real icons; drilling in shows the same, so the popover
        // reads as the bucket opening rather than a different, line-art set of apps.
        realIcons = true,
    )
}

/**
 * The foot of the library (Pixel Edition): one row per non-empty shelf, under the A–Z list and under
 * the bubbles alike. Reads like an app row — glyph, label, a count — with the chevron a folder wears,
 * because tapping it opens a place rather than launching a thing.
 */
@Composable
internal fun ShelfRows(hidden: Int, locked: Int, fg: Color, onOpen: (Shelf) -> Unit) {
    if (hidden == 0 && locked == 0) return
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        if (hidden > 0) ShelfRow(Shelf.HIDDEN, hidden, fg) { onOpen(Shelf.HIDDEN) }
        if (locked > 0) ShelfRow(Shelf.LOCKED, locked, fg) { onOpen(Shelf.LOCKED) }
    }
}

@Composable
private fun ShelfRow(shelf: Shelf, count: Int, fg: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(glyph = shelf.glyph, color = fg, size = 24.dp, strokeWidthUnits = 1.6f)
        Spacer(Modifier.width(15.dp))
        Text(text = "${shelf.label} · $count", color = fg, fontSize = 18.sp, modifier = Modifier.weight(1f))
        LineIcon(glyph = "chevron", color = fg.copy(alpha = 0.45f), size = 17.dp, strokeWidthUnits = 1.6f)
    }
}

/**
 * A shelf opened from its row (Pixel Edition): the same folder-open overlay a bucket uses, so a shelf
 * is a place with the library's own grammar — tap launches, hold raises the strip, whose verbs are
 * the shelf's (Unhide / Unlock, and Info). The Locked shelf only ever reaches here after the host has
 * had Android confirm the device credential.
 */
@Composable
private fun ShelfPopover(
    shelf: Shelf,
    apps: List<AppEntry>,
    tuning: GestureTuning,
    fg: Color,
    accent: Color,
    onLaunch: (AppEntry) -> Unit,
    onHold: (AppEntry, Rect) -> Unit,
    onDismiss: () -> Unit,
) {
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val folder = remember(shelf, apps) {
        FolderItem(name = shelf.label, appKeys = apps.map { it.key }, glyph = shelf.glyph)
    }
    FolderPopover(
        folder = folder,
        appsByKey = byKey,
        glyphFor = { it.glyph },
        tuning = tuning,
        fg = fg,
        accent = accent,
        stripColor = fg.copy(alpha = 0.10f),
        onLaunch = onLaunch,
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxSize(),
        onMemberHold = onHold,
        realIcons = true,
    )
}

@Composable
private fun DrawerAppRow(
    app: AppEntry,
    fg: Color,
    accent: Color,
    badged: Boolean,
    isFavourite: Boolean,
    tuning: GestureTuning,
    onLaunch: () -> Unit,
    onHold: (Rect) -> Unit,
    onPickup: (Rect) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    // Multi-select (Pixel Edition): while selecting, a plain tap is the row's only gesture — it
    // marks (the host decides what the tap does through [onLaunch]) and the hold/pickup race is off.
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    // The strip anchors its Y beside the row that raised it, and a carry lifted off this row is
    // seeded from it, so the row reports where it sits. Root coords, because both are siblings of
    // the drawer at the top of the scene.
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .then(
                if (selecting) {
                    Modifier.clickable { onLaunch() }
                } else {
                    Modifier.itemPress(
                        tuning = tuning,
                        onTap = onLaunch,
                        onHold = { onHold(bounds) },
                        // An app already on the home is not arriving anywhere, so it never lifts: the
                        // hold's menu simply stays up instead. This is the refusal, and it happens
                        // before a single target lights — the alternative is a carry that has to
                        // no-op on release, or a second row launching the same app. Moving a placed
                        // app is done from the home itself; lifting one out of a folder is folder
                        // lifecycle (SID-31).
                        onPickup = { if (!isFavourite) onPickup(bounds) },
                        // itemPress reports positions local to this row; the drop layer thinks in
                        // root coords, and this row already knows where it starts. Both are inert
                        // until something is actually in hand, which is what lets the pickup above
                        // decline on its own.
                        onDrag = { p -> onDrag(bounds.topLeft + p) },
                        onDrop = onDrop,
                    )
                },
            )
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(
            glyph = app.glyph,
            // In-home apps render like any other library row — no accent mark (Oliver's call). The
            // row still declines a pickup (it's already placed); it just no longer says so in colour.
            color = fg,
            size = 24.dp,
            strokeWidthUnits = 1.6f,
            badge = badgeTint(badged, accent),
        )
        Spacer(Modifier.width(15.dp))
        Text(
            text = app.label,
            color = fg,
            fontSize = 18.sp,
            modifier = Modifier.weight(1f),
        )
        // The mark, trailing: an accent check when marked, a quiet ring when not — the Notes page's
        // vocabulary, so a marked thing looks the same everywhere in the launcher.
        if (selecting) {
            Spacer(Modifier.width(12.dp))
            if (selected) {
                LineIcon(glyph = "check", color = accent, size = 20.dp, strokeWidthUnits = 1.8f)
            } else {
                Box(Modifier.size(20.dp).padding(2.dp).border(1.5.dp, fg.copy(alpha = 0.6f), CircleShape))
            }
        }
    }
}

/**
 * Rank matches so what the user is typing leads: exact prefix first, then a word inside
 * the label that starts with the term, then any substring — alphabetical within each tier.
 */
internal fun rankApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val term = query.trim().lowercase()
    if (term.isEmpty()) return apps  // already alphabetical from the repository
    return apps.mapNotNull { app ->
        val label = app.label.lowercase()
        val tier = when {
            label.startsWith(term) -> 0
            label.split(' ', '-', '_', '.', '/').any { it.startsWith(term) } -> 1
            label.contains(term) -> 2
            else -> -1
        }
        if (tier < 0) null else Triple(tier, label, app)
    }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
}

private fun toItems(ranked: List<AppEntry>, query: String): List<DrawerItem> {
    if (ranked.isEmpty()) return emptyList()
    // while searching, results are relevance-ranked → a flat list, no A–Z headers
    if (query.trim().isNotEmpty()) return ranked.map { AppRow(it) }
    // browsing: quiet A–Z section letters
    val out = ArrayList<DrawerItem>()
    var last: String? = null
    for (a in ranked) {
        val letter = a.label.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        if (letter != last) {
            out.add(SectionHeader(letter))
            last = letter
        }
        out.add(AppRow(a))
    }
    return out
}
