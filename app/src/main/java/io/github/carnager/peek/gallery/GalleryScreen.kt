package io.github.carnager.peek.gallery

import android.net.Uri
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.carnager.peek.ui.bounceClick
import io.github.carnager.peek.ui.brandFont
import io.github.carnager.peek.viewer.ImageViewer
import io.github.carnager.peek.viewer.ViewerItem
import io.github.carnager.peek.viewer.share
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun MediaImage.toViewerItem() = ViewerItem(
    uri = uri,
    name = name,
    mimeType = mimeType,
    width = width,
    height = height,
    size = size,
    date = dateTaken,
    path = relativePath?.trimEnd('/'),
    version = modified,
)

@Composable
fun GalleryScreen(
    vm: GalleryViewModel,
    onEdit: (Uri) -> Unit,
    onDelete: (List<Uri>) -> Unit,
) {
    val albumsGrid = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = vm.openAlbum,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "albums",
        ) { album ->
            if (album == null) AlbumsScreen(vm, albumsGrid) else AlbumScreen(vm, album, onDelete)
        }

        // The viewer sits on top of the album so the grid keeps its scroll position underneath.
        // Remembered without state so the exit animation still knows which page it is closing.
        val last = remember { IntArray(1) }
        vm.viewerIndex?.let { last[0] = it }
        AnimatedVisibility(
            visible = vm.viewerIndex != null,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.88f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
        ) {
            val list = vm.albumImages
            key(vm.viewerKey) {
                ImageViewer(
                    items = remember(list) { list.map { it.toViewerItem() } },
                    startIndex = last[0],
                    onClose = { vm.viewerIndex = null },
                    onEdit = { onEdit(it.uri) },
                    onDelete = { onDelete(listOf(it.uri)) },
                    onPageChange = { if (vm.viewerIndex != null) vm.viewerIndex = it },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsScreen(vm: GalleryViewModel, grid: LazyGridState) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("peek", fontFamily = brandFont(), fontSize = if (scroll.state.collapsedFraction < 0.5f) 40.sp else 24.sp) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { pad ->
        if (vm.loaded && vm.albums.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Noch keine Bilder hier", style = MaterialTheme.typography.titleMedium)
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (vm.images.isNotEmpty()) {
                item(key = "all", span = { GridItemSpan(maxLineSpan) }) {
                    AlbumCard("Alle Bilder", vm.images.size, vm.images.first(), aspect = 1.8f) { vm.openAlbum = ALL_IMAGES }
                }
            }
            items(vm.albums, key = { it.bucketId }) { a ->
                AlbumCard(a.name, a.count, a.cover, Modifier.animateItem()) { vm.openAlbum = a.bucketId }
            }
        }
    }
}

@Composable
private fun AlbumCard(name: String, count: Int, cover: MediaImage, modifier: Modifier = Modifier, aspect: Float = 1f, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .bounceClick(pressedScale = 0.95f, onClick = onClick)
            .clip(RoundedCornerShape(26.dp)),
    ) {
        Thumb(cover, Modifier.fillMaxSize(), size = if (aspect > 1f) 900 else 450)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))
            )
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                name,
                color = Color.White,
                style = if (aspect > 1f) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("$count Bilder", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Thumb(image: MediaImage, modifier: Modifier, size: Int = 300) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx)
            .data(image.uri)
            .memoryCacheKey("thumb$size:${image.uri}#${image.modified}")
            .size(size)
            .crossfade(true)
            .build(),
        contentDescription = image.name,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

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

private fun cellsOf(list: List<MediaImage>): List<Cell> {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumScreen(vm: GalleryViewModel, album: Long, onDelete: (List<Uri>) -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val all = vm.images
    val list = remember(all, album) { vm.imagesOf(album) }
    val cells = remember(list) { cellsOf(list) }
    val selecting = vm.selection.isNotEmpty()
    BackHandler {
        if (selecting) vm.selection = emptySet() else vm.openAlbum = null
    }
    val grid = rememberLazyGridState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
                        IconButton(onClick = { vm.openAlbum = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") }
                    }
                },
                title = {
                    AnimatedContent(selecting, label = "title") { sel ->
                        if (sel) {
                            Text("${vm.selection.size} ausgewählt")
                        } else {
                            Text(vm.titleOf(album), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    AnimatedVisibility(selecting, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        IconButton(onClick = { vm.selection = list.mapTo(HashSet()) { it.id } }) { Icon(Icons.Rounded.SelectAll, "Alle auswählen") }
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
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(vm.columns),
                contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 96.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxSize().pinchColumns { d ->
                    vm.changeColumns(d)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                },
            ) {
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
                            modifier = Modifier.animateItem(),
                            onClick = { if (selecting) vm.toggle(c.image.id) else vm.viewerIndex = c.index },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.toggle(c.image.id)
                            },
                        )
                    }
                }
            }

            // Floating action pill for the selection.
            AnimatedVisibility(
                visible = selecting,
                enter = slideInVertically(spring(dampingRatio = 0.7f)) { it * 2 } + fadeIn(),
                exit = slideOutVertically { it * 2 } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 8.dp,
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillAction(Icons.Rounded.Share, "Teilen") { share(ctx, list.filter { it.id in vm.selection }.map { it.uri }) }
                        Spacer(Modifier.width(4.dp))
                        PillAction(Icons.Rounded.Delete, "Löschen") { onDelete(list.filter { it.id in vm.selection }.map { it.uri }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PillAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).bounceClick(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PhotoCell(
    image: MediaImage,
    selecting: Boolean,
    selected: Boolean,
    columns: Int,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val inset by animateFloatAsState(if (selected) 0.86f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "sel")
    val corner by animateDpAsState(if (selected) 18.dp else 4.dp, label = "corner")
    Box(
        modifier
            .aspectRatio(1f)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(4.dp))
            .bounceClick(pressedScale = 0.94f, onClick = onClick, onLongClick = onLongClick)
    ) {
        Thumb(
            image,
            Modifier.fillMaxSize().graphicsLayer { scaleX = inset; scaleY = inset }.clip(RoundedCornerShape(corner)),
            size = if (columns <= 3) 480 else 300,
        )
        if (image.mimeType == "image/gif") {
            Icon(Icons.Rounded.GifBox, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(18.dp))
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
