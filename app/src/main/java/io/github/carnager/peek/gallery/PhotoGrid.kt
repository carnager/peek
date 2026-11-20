package io.github.carnager.peek.gallery

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.carnager.peek.ui.bounceClick
import io.github.carnager.peek.viewer.share
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface Cell {
    data class Header(val label: String) : Cell
    data class Photo(val index: Int, val image: MediaImage) : Cell
}

private fun dayLabel(ms: Long, now: Calendar): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return when {
        DateUtils.isToday(ms) -> "Heute"
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> "Gestern"
        now.timeInMillis - ms < 6 * DateUtils.DAY_IN_MILLIS -> SimpleDateFormat("EEEE", Locale.getDefault()).format(c.time)
        c.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat("EEE, d. MMMM", Locale.getDefault()).format(c.time)
        else -> SimpleDateFormat("d. MMMM yyyy", Locale.getDefault()).format(c.time)
    }
}

private fun cellsOf(list: List<MediaImage>, headers: Boolean): List<Cell> {
    if (!headers) return list.mapIndexed { i, img -> Cell.Photo(i, img) }
    val now = Calendar.getInstance()
    val out = ArrayList<Cell>(list.size + 32)
    var last: String? = null
    list.forEachIndexed { i, img ->
        val label = dayLabel(img.dateTaken, now)
        if (label != last) {
            out += Cell.Header(label)
            last = label
        }
        out += Cell.Photo(i, img)
    }
    return out
}

/** Pinch the grid to change how many thumbnails fit in a row; single-finger scrolling is left alone. */
private fun Modifier.pinchColumns(onChange: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (ev.changes.count { it.pressed } >= 2) {
                zoom *= ev.calculateZoom()
                ev.changes.forEach { it.consume() }
                if (zoom > 1.3f) { onChange(-1); zoom = 1f }
                if (zoom < 0.77f) { onChange(1); zoom = 1f }
            }
        } while (ev.changes.any { it.pressed })
    }
}

/** Key of the grid item under [pos] (grid coordinates), if any. */
private fun LazyGridState.keyAt(pos: Offset): Any? {
    val info = layoutInfo
    return info.visibleItemsInfo.firstOrNull {
        val top = it.offset.y - info.viewportStartOffset
        val left = it.offset.x
        pos.y >= top && pos.y < top + it.size.height && pos.x >= left && pos.x < left + it.size.width
    }?.key
}

/**
 * Long-press a photo and drag to select everything between it and the finger, like in Google
 * Photos. Near the top or bottom edge the grid scrolls along. Runs before the cells see the touch,
 * so releasing after a long press does not count as a tap.
 */
private fun Modifier.dragSelect(
    grid: LazyGridState,
    cells: () -> List<Cell>,
    selection: () -> Set<Long>,
    onSelect: (Set<Long>) -> Unit,
    haptics: HapticFeedback,
    scope: CoroutineScope,
    edgePx: Float,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Wait for the long press; any real movement first means the user is scrolling.
        var lifted = false
        val held = withTimeoutOrNull(longPress) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (!ch.pressed) { lifted = true; return@withTimeoutOrNull false }
                if (ev.changes.size > 1 || (ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (held != null || lifted) return@awaitEachGesture

        val all = cells()
        // Grid positions and cell positions differ (the trash has an extra row on top), so go by key.
        val indexOfId = HashMap<Long, Int>(all.size).apply {
            all.forEachIndexed { i, c -> if (c is Cell.Photo) put(c.image.id, i) }
        }
        fun cellAt(p: Offset): Int? = (grid.keyAt(p) as? Long)?.let { indexOfId[it] }
        val anchor = cellAt(down.position) ?: return@awaitEachGesture
        val base = selection()
        val adding = (all[anchor] as Cell.Photo).image.id !in base
        var lastIdx = -1
        fun update(idx: Int) {
            if (idx == lastIdx) return
            lastIdx = idx
            val range = (minOf(anchor, idx)..maxOf(anchor, idx)).mapNotNull { (all.getOrNull(it) as? Cell.Photo)?.image?.id }
            onSelect(if (adding) base + range else base - range.toSet())
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        update(anchor)

        var pos = down.position
        var speed = 0f
        val scroller: Job = scope.launch {
            while (isActive) {
                if (speed != 0f) {
                    grid.scrollBy(speed)
                    cellAt(pos)?.let(::update)
                }
                delay(16)
            }
        }
        try {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                ev.changes.forEach { it.consume() }
                if (!ch.pressed) break
                pos = ch.position
                speed = when {
                    pos.y < edgePx -> -((edgePx - pos.y) / edgePx).coerceIn(0f, 1f) * 28f
                    pos.y > size.height - edgePx -> ((pos.y - (size.height - edgePx)) / edgePx).coerceIn(0f, 1f) * 28f
                    else -> 0f
                }
                cellAt(pos)?.let(::update)
            }
        } finally {
            scroller.cancel()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoGridScreen(vm: GalleryViewModel, ops: MediaOps, source: Source) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val isTrash = source == Source.Trash
    val userAlbum = (source as? Source.User)?.let { s -> vm.userAlbums.firstOrNull { it.id == s.id } }
    val list = remember(vm.images, vm.trash, vm.userAlbums, source) { vm.imagesOf(source) }
    val cells = remember(list, isTrash) { cellsOf(list, headers = !isTrash) }
    val latestCells by rememberUpdatedState(cells)
    val selecting = vm.selection.isNotEmpty()
    val selected = remember(list, vm.selection) { list.filter { it.id in vm.selection } }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    BackHandler {
        if (selecting) vm.selection = emptySet() else vm.open = null
    }
    val grid = rememberLazyGridState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val edge = with(LocalDensity.current) { 72.dp.toPx() }

    // After closing the viewer, make sure the photo that was last on screen is visible in the grid.
    var lastViewed by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(vm.viewerIndex) {
        val v = vm.viewerIndex
        if (v != null) {
            lastViewed = v
        } else {
            lastViewed?.let { i ->
                val cell = cells.indexOfFirst { it is Cell.Photo && it.index == i }
                val visible = grid.layoutInfo.visibleItemsInfo.any { it.index == cell }
                if (cell >= 0 && !visible) grid.scrollToItem(cell)
            }
            lastViewed = null
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { vm.selection = emptySet() }) { Icon(Icons.Rounded.Close, "Auswahl beenden") }
                    } else {
                        IconButton(onClick = { vm.open = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") }
                    }
                },
                title = {
                    AnimatedContent(selecting, label = "title") { sel ->
                        if (sel) {
                            Text("${vm.selection.size} ausgewählt")
                        } else {
                            Text(vm.titleOf(source), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { vm.selection = list.mapTo(HashSet()) { it.id } }) { Icon(Icons.Rounded.SelectAll, "Alle auswählen") }
                    } else if (isTrash && list.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) { Text("Leeren") }
                    } else if (userAlbum != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mehr") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                                DropdownMenuItem(
                                    text = { Text("Umbenennen") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { menu = false; renaming = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Album löschen") },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                    onClick = { menu = false; vm.deleteAlbum(userAlbum.id) },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
            if (vm.loaded && list.isEmpty()) {
                EmptyState(source, Modifier.padding(pad))
            }
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(vm.columns),
                contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 110.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .pinchColumns { d ->
                        vm.changeColumns(d)
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    .dragSelect(grid, { latestCells }, { vm.selection }, { vm.selection = it }, haptics, scope, edge),
            ) {
                if (isTrash && list.isNotEmpty()) {
                    item(key = "trashInfo", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Bilder im Papierkorb werden nach 30 Tagen endgültig gelöscht.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                        )
                    }
                }
                items(
                    cells,
                    key = { c -> if (c is Cell.Photo) c.image.id else "h:" + (c as Cell.Header).label },
                    span = { c -> if (c is Cell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                    contentType = { it::class },
                ) { c ->
                    when (c) {
                        is Cell.Header -> Text(
                            c.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.animateItem().padding(start = 13.dp, top = 18.dp, bottom = 8.dp),
                        )
                        is Cell.Photo -> PhotoCell(
                            image = c.image,
                            selecting = selecting,
                            selected = c.image.id in vm.selection,
                            columns = vm.columns,
                            showFavorite = !isTrash && source != Source.Favorites,
                            daysLeft = if (isTrash) c.image.expires.takeIf { it > 0 }?.let { ((it - System.currentTimeMillis()) / DateUtils.DAY_IN_MILLIS).toInt().coerceAtLeast(0) } else null,
                            modifier = Modifier.animateItem(),
                            hidden = vm.hero?.image?.id == c.image.id,
                            onBounds = { vm.thumbBounds[c.image.id] = it },
                            onGone = { vm.thumbBounds.remove(c.image.id) },
                            onClick = {
                                if (selecting || isTrash) vm.toggle(c.image.id) else vm.openViewer(c.index, c.image)
                            },
                        )
                    }
                }
            }

            FastScroller(
                grid = grid,
                labelAt = { idx ->
                    val photo = cells.subList(idx.coerceIn(0, cells.lastIndex), cells.size).firstOrNull { it is Cell.Photo } as? Cell.Photo
                    photo?.let { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(it.image.dateTaken) } ?: ""
                },
                count = cells.size,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp),
            )

            // Floating action pill for the selection.
            AnimatedVisibility(
                visible = selecting,
                enter = slideInVertically(spring(dampingRatio = 0.7f)) { it * 2 } + fadeIn(),
                exit = slideOutVertically { it * 2 } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 8.dp) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isTrash) {
                            PillAction(Icons.Rounded.RestoreFromTrash, "Wiederherstellen") { ops.restore(selected) }
                            PillAction(Icons.Rounded.DeleteForever, "Löschen") { ops.deleteForever(selected) }
                        } else {
                            val allFav = selected.isNotEmpty() && selected.all { it.isFavorite }
                            PillAction(Icons.Rounded.Share, "Teilen") {
                                val mime = when {
                                    selected.all { it.isVideo } -> "video/*"
                                    selected.none { it.isVideo } -> "image/*"
                                    else -> "*/*"
                                }
                                share(ctx, selected.map { it.uri }, mime)
                            }
                            PillAction(if (allFav) Icons.Rounded.HeartBroken else Icons.Rounded.FavoriteBorder, if (allFav) "Kein Fav." else "Favorit") {
                                ops.setFavorite(selected, !allFav)
                            }
                            PillAction(Icons.Rounded.PhotoAlbum, "Album") { vm.addToAlbum = selected.map { it.id } }
                            if (userAlbum != null) {
                                PillAction(Icons.Rounded.RemoveCircleOutline, "Entfernen") {
                                    vm.removeFrom(userAlbum.id, selected.map { it.id })
                                    vm.selection = emptySet()
                                }
                            }
                            PillAction(Icons.Rounded.DeleteOutline, "Löschen") { ops.trash(selected) }
                        }
                    }
                }
            }
        }
    }

    if (renaming && userAlbum != null) {
        NameDialog("Album umbenennen", userAlbum.name, "Speichern", onDismiss = { renaming = false }) {
            vm.renameAlbum(userAlbum.id, it)
            renaming = false
        }
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Papierkorb leeren?") },
            text = { Text("${list.size} Bilder werden endgültig gelöscht.") },
            confirmButton = {
                Button(
                    onClick = { confirmEmpty = false; ops.deleteForever(list) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Endgültig löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun EmptyState(source: Source, modifier: Modifier) {
    val (icon, title, text) = when (source) {
        Source.Favorites -> Triple(Icons.Rounded.FavoriteBorder, "Noch keine Favoriten", "Tippe im Viewer aufs Herz, um ein Bild hier zu sammeln.")
        Source.Trash -> Triple(Icons.Rounded.DeleteOutline, "Papierkorb ist leer", "Gelöschte Bilder landen hier und bleiben 30 Tage wiederherstellbar.")
        is Source.User -> Triple(Icons.Rounded.PhotoAlbum, "Album ist leer", "Wähle Bilder in einem Ordner aus und tippe auf „Album“.")
        else -> Triple(Icons.Rounded.PhotoLibrary, "Keine Bilder", "")
    }
    Column(
        modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The elephant, with the topic's icon as a small badge.
        Box {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(io.github.carnager.peek.R.drawable.mascot), null, Modifier.size(width = 120.dp, height = 84.dp))
            Box(
                Modifier.align(Alignment.BottomEnd).size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (text.isNotEmpty()) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * A thumb on the right edge that appears while scrolling; drag it to jump through years of
 * photos, with a bubble showing the month you are at.
 */
@Composable
private fun FastScroller(grid: LazyGridState, labelAt: (Int) -> String, count: Int, modifier: Modifier) {
    if (count < 60) return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    val active = grid.isScrollInProgress || dragging
    LaunchedEffect(active) {
        if (active) show = true else { delay(1400); show = false }
    }
    val alpha by animateFloatAsState(if (show) 1f else 0f, label = "scroller")
    val density = LocalDensity.current
    val thumbH = with(density) { 52.dp.toPx() }

    BoxWithConstraints(modifier.fillMaxHeight().width(160.dp).graphicsLayer { this.alpha = alpha }) {
        val track = (constraints.maxHeight - thumbH).coerceAtLeast(1f)
        val first = grid.firstVisibleItemIndex
        var dragPos by remember { mutableFloatStateOf(0f) }
        val y = if (dragging) dragPos else track * first / (count - 1).coerceAtLeast(1)
        var lastLabel by remember { mutableStateOf("") }

        if (dragging) {
            val label = labelAt(first)
            LaunchedEffect(label) {
                if (lastLabel.isNotEmpty() && label != lastLabel) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                lastLabel = label
            }
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-with(density) { 44.dp.roundToPx() }, (y + thumbH / 2 - with(density) { 20.dp.toPx() }).roundToInt()) }
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y.roundToInt()) }
                .size(width = 36.dp, height = 52.dp)
                .pointerInput(count, track) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            if (!show) return@detectVerticalDragGestures
                            dragging = true
                            dragPos = track * grid.firstVisibleItemIndex / (count - 1).coerceAtLeast(1)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dy ->
                        if (!dragging) return@detectVerticalDragGestures
                        change.consume()
                        dragPos = (dragPos + dy).coerceIn(0f, track)
                        val target = (dragPos / track * (count - 1)).roundToInt()
                        scope.launch { grid.scrollToItem(target) }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size(width = if (dragging) 10.dp else 6.dp, height = 44.dp)
                    .clip(CircleShape)
                    .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)),
            )
        }
    }
}

@Composable
private fun PillAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(22.dp)).bounceClick(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun PhotoCell(
    image: MediaImage,
    selecting: Boolean,
    selected: Boolean,
    columns: Int,
    showFavorite: Boolean,
    daysLeft: Int?,
    modifier: Modifier,
    hidden: Boolean,
    onBounds: (Rect) -> Unit,
    onGone: () -> Unit,
    onClick: () -> Unit,
) {
    DisposableEffect(image.id) { onDispose(onGone) }
    val inset by animateFloatAsState(if (selected) 0.86f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "sel")
    val corner by animateDpAsState(if (selected) 18.dp else 4.dp, label = "corner")
    Box(
        modifier
            .aspectRatio(1f)
            .onGloballyPositioned { c ->
                val p = c.positionInRoot()
                onBounds(Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height))
            }
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(4.dp))
            .bounceClick(pressedScale = 0.94f, onClick = onClick)
    ) {
        Thumb(
            image,
            Modifier.fillMaxSize().graphicsLayer { scaleX = inset; scaleY = inset }.clip(RoundedCornerShape(corner)),
            size = thumbSize(columns),
        )
        if (image.isVideo) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(start = 3.dp, end = 7.dp, top = 1.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(io.github.carnager.peek.viewer.formatDuration(image.duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (image.mimeType == "image/gif") {
            Icon(Icons.Rounded.GifBox, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(18.dp))
        }
        if (showFavorite && image.isFavorite) {
            Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(16.dp))
        }
        if (daysLeft != null) {
            Text(
                if (daysLeft == 1) "1 Tag" else "$daysLeft Tage",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        AnimatedVisibility(
            visible = selecting,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        ) {
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f))
                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
