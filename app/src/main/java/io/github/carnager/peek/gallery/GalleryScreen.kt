package io.github.carnager.peek.gallery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.carnager.peek.ui.bounceClick
import io.github.carnager.peek.ui.brandFont
import io.github.carnager.peek.viewer.ImageViewer
import io.github.carnager.peek.viewer.ViewerItem
import kotlin.math.roundToInt

fun thumbSize(columns: Int) = if (columns <= 3) 480 else 300
fun thumbKey(image: MediaImage, size: Int) = "thumb$size:${image.uri}#${image.modified}"

/** What Coil loads for a thumbnail: the server renders its own for trunk photos. */
fun MediaImage.thumbData(size: Int): Any =
    remote?.let { io.github.carnager.peek.trunk.TrunkAccount.thumbUrl(it.id, if (size >= 480) "medium" else "small") } ?: uri

fun MediaImage.toViewerItem(placeholderKey: String? = null) = ViewerItem(
    uri = uri,
    name = name,
    mimeType = mimeType,
    width = width,
    height = height,
    size = size,
    date = dateTaken,
    path = relativePath?.trimEnd('/'),
    version = modified,
    placeholderKey = placeholderKey,
    favorite = isFavorite,
    isVideo = isVideo,
    duration = duration,
    remote = remote != null,
    previewUrl = remote?.takeIf { !it.isVideo }?.let { io.github.carnager.peek.trunk.TrunkAccount.thumbUrl(it.id, "preview") },
    meta = remote?.let { r ->
        io.github.carnager.peek.viewer.PhotoMeta(
            camera = when {
                r.model == null -> r.make
                r.make == null || r.model.startsWith(r.make, ignoreCase = true) -> r.model
                else -> "${r.make} ${r.model}"
            },
            lens = r.lens,
            aperture = r.fNumber,
            exposure = r.exposure,
            iso = r.iso,
            focal = r.focalLength,
            lat = r.lat,
            lon = r.lon,
            altitude = r.alt,
        )
    },
)

@Composable
fun GalleryScreen(vm: GalleryViewModel, ops: MediaOps) {
    val albumsGrid = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message?.token) {
        val m = vm.message ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar(m.text, m.action, withDismissAction = false, duration = if (m.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) m.onAction?.invoke()
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = vm.open,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "albums",
        ) { source ->
            if (source == null) AlbumsScreen(vm, ops, albumsGrid) else PhotoGridScreen(vm, ops, source)
        }

        // The viewer sits on top of the album so the grid keeps its scroll position underneath.
        val last = remember { IntArray(1) }
        vm.viewerIndex?.let { last[0] = it }
        val hero = vm.hero
        val heroProgress = remember { Animatable(0f) }
        LaunchedEffect(hero?.token) {
            val h = hero ?: return@LaunchedEffect
            if (h.opening) {
                heroProgress.snapTo(0f)
                heroProgress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 420f))
            } else {
                heroProgress.snapTo(1f)
                heroProgress.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 500f))
            }
            if (vm.hero?.token == h.token) vm.hero = null
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()

            if (vm.viewerIndex != null) {
                val list = vm.openImages
                val ph = thumbSize(vm.columns)
                key(vm.viewerKey) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (vm.hero?.opening == true) 0f else 1f }) {
                        ImageViewer(
                            items = remember(list, ph) { list.map { it.toViewerItem(thumbKey(it, ph)) } },
                            startIndex = last[0],
                            onClose = {
                                closeHero(vm, list, screenW, screenH, 0f, 1f, 1f)
                                vm.viewerIndex = null
                            },
                            onSwipeClose = { dy, sc, bg ->
                                closeHero(vm, list, screenW, screenH, dy, sc, bg)
                                vm.viewerIndex = null
                            },
                            onEdit = { ops.edit(it.uri) },
                            onDelete = { item ->
                                list.firstOrNull { it.uri == item.uri }?.let { if (it.remote != null) vm.trunkTrash(listOf(it)) else ops.trash(listOf(it)) }
                            },
                            onFavorite = { item ->
                                list.firstOrNull { it.uri == item.uri }?.let {
                                    if (it.remote != null) vm.trunkFavorite(listOf(it), !it.isFavorite) else ops.setFavorite(listOf(it), !it.isFavorite)
                                }
                            },
                            // Albums hold phone photos; server photos would need server albums.
                            onAddToAlbum = if (vm.open == Source.Trunk) null else { item -> list.firstOrNull { it.uri == item.uri }?.let { vm.addToAlbum = listOf(it.id) } },
                            onPageChange = { if (vm.viewerIndex != null) vm.viewerIndex = it },
                        )
                    }
                }
            }

            if (hero != null) {
                val p = heroProgress.value
                val target = fitRect(hero.image, screenW, screenH)
                val (rect, backdrop, imgAlpha) = if (hero.opening) {
                    Triple(lerpRect(hero.from, target, p), p, 1f)
                } else {
                    // The grid cell is read live, so it follows the grid scrolling the photo into view.
                    val cell = vm.thumbBounds[hero.image.id]
                    Triple(lerpRect(cell ?: hero.from, hero.from, p), p * hero.backdrop, if (cell == null) p else 1f)
                }
                val density = LocalDensity.current
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = backdrop.coerceIn(0f, 1f))))
                val size = thumbSize(vm.columns)
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(hero.image.thumbData(size))
                        .memoryCacheKey(thumbKey(hero.image, size))
                        .placeholderMemoryCacheKey(thumbKey(hero.image, size))
                        .size(size)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                        .graphicsLayer { alpha = imgAlpha.coerceIn(0f, 1f) }
                        .clip(RoundedCornerShape(lerp(4.dp, 0.dp, p.coerceIn(0f, 1f)))),
                )
            }
        }

        // Keep messages clear of whichever floating bar is showing: the viewer's actions or the
        // selection pill (both about 60dp tall, 20dp above the navigation bar).
        val barShown = vm.viewerIndex != null || vm.selection.isNotEmpty()
        val snackbarLift by androidx.compose.animation.core.animateDpAsState(if (barShown) 96.dp else 16.dp, label = "snackbarLift")
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = snackbarLift),
        ) { data ->
            Snackbar(data, shape = RoundedCornerShape(18.dp))
        }
    }

    vm.addToAlbum?.let { ids ->
        AddToAlbumSheet(vm, ids) { vm.addToAlbum = null }
    }
}

/** Where the viewer shows [image] when it is fitted to the screen. */
private fun fitRect(image: MediaImage, w: Float, h: Float): Rect {
    if (image.width <= 0 || image.height <= 0) return Rect(0f, 0f, w, h)
    val s = minOf(w / image.width, h / image.height)
    val iw = image.width * s
    val ih = image.height * s
    return Rect((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
}

private fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)

/** Starts the fly-back from the viewer's current photo position into its grid cell. */
private fun closeHero(vm: GalleryViewModel, list: List<MediaImage>, w: Float, h: Float, dy: Float, scale: Float, backdrop: Float) {
    val image = list.getOrNull(vm.viewerIndex ?: return) ?: return
    val fit = fitRect(image, w, h)
    val cx = w / 2
    val cy = h / 2
    val from = Rect(
        cx + (fit.left - cx) * scale,
        cy + (fit.top - cy) * scale + dy,
        cx + (fit.right - cx) * scale,
        cy + (fit.bottom - cy) * scale + dy,
    )
    vm.hero = Hero(image, from, opening = false, backdrop = backdrop)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsScreen(vm: GalleryViewModel, ops: MediaOps, grid: LazyGridState) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var creating by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    val upload = rememberUploadProgress(vm)
    // Re-checked on every recomposition, so returning from the settings page updates it.
    var canManage by remember { mutableStateOf(ops.canManageMedia) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        canManage = ops.canManageMedia
        onPauseOrDispose {}
    }
    val byId = remember(vm.images) { vm.images.associateBy { it.id } }
    // When the photos arrive after the cards below them, show the "all photos" card instead of
    // keeping the list anchored on whatever was first before.
    val hasImages = vm.images.isNotEmpty()
    LaunchedEffect(hasImages) {
        if (hasImages && grid.firstVisibleItemIndex <= 2) grid.scrollToItem(0)
    }

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
        if (vm.loaded && vm.images.isEmpty() && vm.trash.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(io.github.carnager.peek.R.drawable.mascot), null, Modifier.size(width = 120.dp, height = 84.dp))
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
            if (!canManage && !vm.manageMediaHintDismissed && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                    ManageMediaHint(onAllow = ops::requestManageMedia, onDismiss = vm::dismissManageMediaHint)
                }
            }
            if (vm.images.isNotEmpty()) {
                item(key = "all", span = { GridItemSpan(maxLineSpan) }) {
                    AlbumCard("Alle Bilder", vm.images.size, vm.images.first(), aspect = 1.8f) { vm.open = Source.All }
                }
            }
            item(key = "quick", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    QuickTile(Icons.Rounded.Favorite, "Favoriten", vm.favorites.size, Color(0xFFFF4D6D), Modifier.weight(1f)) { vm.open = Source.Favorites }
                    QuickTile(Icons.Rounded.DeleteOutline, "Papierkorb", vm.trash.size, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f)) { vm.open = Source.Trash }
                }
            }

            item(key = "trunk", span = { GridItemSpan(maxLineSpan) }) {
                TrunkCard(vm, upload) { if (vm.trunkConnected) vm.open = Source.Trunk else connecting = true }
            }
            item(key = "albumsHeader", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("Alben") {
                    FilledTonalButton(onClick = { creating = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Neu")
                    }
                }
            }
            if (vm.userAlbums.isEmpty()) {
                item(key = "albumsEmpty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Sammle Bilder aus allen Ordnern in eigenen Alben. Die Dateien bleiben, wo sie sind.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(vm.userAlbums, key = { "u:" + it.id }) { a ->
                val cover = remember(a.ids, byId) { a.ids.firstNotNullOfOrNull { byId[it] } }
                val count = remember(a.ids, byId) { a.ids.count { it in byId } }
                if (cover != null) {
                    AlbumCard(a.name, count, cover, Modifier.animateItem()) { vm.open = Source.User(a.id) }
                } else {
                    EmptyAlbumCard(a.name, Modifier.animateItem()) { vm.open = Source.User(a.id) }
                }
            }

            item(key = "foldersHeader", span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Ordner") }
            items(vm.folders, key = { "f:" + it.bucketId }) { a ->
                AlbumCard(a.name, a.count, a.cover, Modifier.animateItem()) { vm.open = Source.Folder(a.bucketId) }
            }
        }
    }

    if (creating) {
        NameDialog(title = "Neues Album", initial = "", confirm = "Anlegen", onDismiss = { creating = false }) { name ->
            creating = false
            vm.open = Source.User(vm.createAlbum(name).id)
        }
    }
    if (connecting) {
        TrunkConnectDialog(vm, onDone = { connecting = false; if (vm.trunkConnected) vm.open = Source.Trunk })
    }
}

@Composable
private fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, count: Int, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .bounceClick(pressedScale = 0.95f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ManageMediaHint(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp, end = 4.dp)) {
            Icon(Icons.Rounded.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Ohne Rückfragen löschen und favorisieren", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Erlaube peek die Medienverwaltung, dann fragt Android nicht bei jedem Papierkorb oder Herz nach.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
                TextButton(onClick = onAllow, contentPadding = PaddingValues(0.dp)) { Text("Erlauben") }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Ausblenden") }
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
            Text(if (count == 1) "1 Bild" else "$count Bilder", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmptyAlbumCard(name: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .bounceClick(pressedScale = 0.95f, onClick = onClick),
    ) {
        Icon(
            Icons.Rounded.PhotoAlbum, null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.Center).size(48.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Leer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Thumb(image: MediaImage, modifier: Modifier, size: Int = 300) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx)
            .data(image.thumbData(size))
            .memoryCacheKey(thumbKey(image, size))
            .size(size)
            .crossfade(true)
            .build(),
        contentDescription = image.name,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}
