package io.github.carnager.peek.gallery

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBar
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
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Settings
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
    remote?.let { io.github.carnager.peek.trunk.TrunkAccount.thumbUrl(it, if (size >= 480) "medium" else "small") } ?: uri

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
    edited = remote?.edited == true,
    owner = remote?.owner,
    previewUrl = remote?.takeIf { !it.isVideo }?.let { io.github.carnager.peek.trunk.TrunkAccount.thumbUrl(it, "preview") },
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
            place = r.placeLine,
            caption = r.caption,
        )
    },
)

@Composable
fun GalleryScreen(vm: GalleryViewModel, ops: MediaOps) {
    // Sheets the viewer opens for trunk photos.
    var viewerSend by remember { mutableStateOf<List<MediaImage>?>(null) }
    var viewerLink by remember { mutableStateOf<List<MediaImage>?>(null) }
    val ctx = LocalContext.current
    // One scroll position per tab, kept while an album or folder is open.
    val tabGrids = Tab.entries.associateWith { rememberLazyGridState() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message?.token) {
        val m = vm.message ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar(m.text, m.action, withDismissAction = false, duration = if (m.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) m.onAction?.invoke()
    }

    Box(Modifier.fillMaxSize()) {
        // The trunk tab's albums and memories: load the cached library, then ask what changed.
        LaunchedEffect(vm.trunkConnected) { if (vm.trunkConnected) vm.openTrunk() }
        AnimatedContent(
            targetState = vm.open ?: vm.tab,
            transitionSpec = {
                if (initialState is Tab && targetState is Tab) {
                    fadeIn() togetherWith fadeOut()
                } else if (targetState is Source) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "albums",
        ) { screen ->
            val tabBar: @Composable () -> Unit = { TabBar(vm, tabGrids) }
            when {
                screen is Source -> PhotoGridScreen(vm, ops, screen)
                screen == Tab.Phone -> PhotoGridScreen(
                    vm, ops, Source.All, root = true, grid = tabGrids.getValue(Tab.Phone),
                    header = { PhoneHeader(vm, ops) }, bottomBar = tabBar,
                )
                screen == Tab.Trunk && vm.trunkConnected -> PhotoGridScreen(
                    vm, ops, Source.Trunk, root = true, grid = tabGrids.getValue(Tab.Trunk),
                    header = { TrunkHeader(vm) }, bottomBar = tabBar,
                )
                else -> AlbumsScreen(vm, tabGrids, tabBar)
            }
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
                            onEdit = { item ->
                                val img = list.firstOrNull { it.uri == item.uri }
                                val remote = img?.remote
                                if (img != null && remote != null) vm.withTrunkOriginals(listOf(img)) { ops.editTrunk(it[0], remote) } else ops.edit(item.uri)
                            },
                            onShare = if (!vm.shown.onTrunk) null else { item ->
                                list.firstOrNull { it.uri == item.uri }?.let { img ->
                                    vm.withTrunkOriginals(listOf(img)) { io.github.carnager.peek.viewer.share(ctx, it, img.mimeType) }
                                }
                            },
                            onDelete = { item ->
                                list.firstOrNull { it.uri == item.uri }?.let { if (it.remote != null) vm.trunkTrash(listOf(it)) else ops.trash(listOf(it)) }
                            },
                            onFavorite = { item ->
                                list.firstOrNull { it.uri == item.uri }?.let {
                                    if (it.remote != null) vm.trunkFavorite(listOf(it), !it.isFavorite) else ops.setFavorite(listOf(it), !it.isFavorite)
                                }
                            },
                            onCaption = if (!vm.shown.onTrunk) null else { item, caption ->
                                list.firstOrNull { it.uri == item.uri }?.let { vm.trunkCaption(it, caption) }
                            },
                            onSend = if (!vm.shown.onTrunk) null else { item ->
                                list.firstOrNull { it.uri == item.uri }?.let { viewerSend = listOf(it) }
                            },
                            onLink = if (!vm.shown.onTrunk) null else { item ->
                                list.firstOrNull { it.uri == item.uri }?.let { viewerLink = listOf(it) }
                            },
                            onRevertEdit = if (!vm.shown.onTrunk) null else { item ->
                                list.firstOrNull { it.uri == item.uri }?.let { vm.trunkRevertEdit(it) }
                            },
                            // Phone photos go into peek's albums, server photos into trunk's.
                            onAddToAlbum = { item -> list.firstOrNull { it.uri == item.uri }?.let { vm.addToAlbum = listOf(it.id) } },
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

        if (vm.settingsOpen) io.github.carnager.peek.settings.SettingsScreen(vm, ops) { vm.settingsOpen = false }
        vm.review?.let { io.github.carnager.peek.review.ReviewScreen(vm, ops, it) }

        // Keep messages clear of whichever bar is showing: the viewer's actions, the selection
        // pill or the review's buttons (all about 60dp tall, near the navigation bar).
        val barShown = vm.viewerIndex != null || vm.selection.isNotEmpty() || vm.review != null
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
    viewerSend?.let { SendSheet(vm, it) { viewerSend = null } }
    viewerLink?.let { LinkSheet(vm, null, it, "Foto") { viewerLink = null } }
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

/** The tabs below the overview; tapping the open one again scrolls it to the top. */
@Composable
private fun TabBar(vm: GalleryViewModel, grids: Map<Tab, LazyGridState>) {
    val scope = rememberCoroutineScope()
    NavigationBar {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = vm.tab == t,
                onClick = { if (vm.tab == t) scope.launch { grids.getValue(t).animateScrollToItem(0) } else vm.selectTab(t) },
                icon = {
                    Icon(
                        when (t) {
                            Tab.Phone -> Icons.Rounded.Smartphone
                            Tab.Trunk -> Icons.Rounded.Cloud
                            Tab.Folders -> Icons.Rounded.Folder
                        },
                        null,
                    )
                },
                label = { Text(t.label) },
            )
        }
    }
}

/** The review and settings buttons, in the bar of every tab. */
@Composable
internal fun RootActions(vm: GalleryViewModel) {
    // The manual way into the nightly review, once it is set up; the badge counts what waits.
    if (vm.reviewSettings.configured) {
        val pending = vm.reviewPending.size
        IconButton(onClick = vm::startReview) {
            BadgedBox(badge = { if (pending > 0) Badge { Text(if (pending > 99) "99+" else "$pending") } }) {
                Icon(Icons.Rounded.Checklist, "Neue Bilder durchsehen")
            }
        }
    }
    IconButton(onClick = { vm.settingsOpen = true }) { Icon(Icons.Rounded.Settings, "Einstellungen") }
}

/** Tabs that are not a photo grid: the folders, and trunk while it is not connected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsScreen(vm: GalleryViewModel, grids: Map<Tab, LazyGridState>, tabBar: @Composable () -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var connecting by remember { mutableStateOf(false) }
    val t = vm.tab
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(if (t == Tab.Trunk) "trunk" else "peek", fontFamily = brandFont(), fontSize = if (scroll.state.collapsedFraction < 0.5f) 40.sp else 24.sp) },
                actions = { RootActions(vm) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = tabBar,
    ) { pad ->
        LazyVerticalGrid(
            state = grids.getValue(t),
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (t == Tab.Trunk) {
                item(key = "trunk", span = ::full) { TrunkCard(vm, null) { connecting = true } }
                item(key = "trunkIntro", span = ::full) {
                    Hint("trunk ist dein eigener Server für Fotos und Videos: Sicherung, Alben, Teilen, auf allen Geräten. Verbinde peek oben mit deinem trunk.")
                }
            } else {
                if (vm.folders.isEmpty()) {
                    item(key = "foldersEmpty", span = ::full) { Hint("Noch keine Ordner mit Bildern auf dem Handy.") }
                }
                items(vm.folders, key = { "f:" + it.bucketId }) { a ->
                    AlbumCard(a.name, a.count, a.cover, Modifier.animateItem()) { vm.open = Source.Folder(a.bucketId) }
                }
            }
        }
    }
    if (connecting) TrunkConnectDialog(vm, onDone = { connecting = false })
}

/** A grid item across the whole row. */
private fun full(s: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope) = GridItemSpan(s.maxLineSpan)

private fun countLabel(n: Int) = when (n) {
    0 -> "Leer"
    1 -> "1 Bild"
    else -> "$n Bilder"
}

/** Above the phone's photos: highlights, then peek's albums. */
@Composable
private fun PhoneHeader(vm: GalleryViewModel, ops: MediaOps) {
    var creating by remember { mutableStateOf(false) }
    // Re-checked when coming back, so returning from the settings page updates it.
    var canManage by remember { mutableStateOf(ops.canManageMedia) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        canManage = ops.canManageMedia
        onPauseOrDispose {}
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (!canManage && !vm.manageMediaHintDismissed && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Box(Modifier.padding(horizontal = 13.dp, vertical = 6.dp)) {
                ManageMediaHint(onAllow = ops::requestManageMedia, onDismiss = vm::dismissManageMediaHint)
            }
        }
        Strip {
            vm.phoneMemories?.let { m ->
                item(key = "memories") { MemoriesTile(m) { vm.open = Source.Memories(false) } }
            }
            item(key = "fav") {
                StripCard("Favoriten", countLabel(vm.favorites.size), vm.favorites.firstOrNull(), Icons.Rounded.Favorite, Color(0xFFFF4D6D)) { vm.open = Source.Favorites }
            }
            item(key = "trash") {
                StripCard("Papierkorb", countLabel(vm.trash.size), null, Icons.Rounded.DeleteOutline, MaterialTheme.colorScheme.onSurfaceVariant) { vm.open = Source.Trash }
            }
        }
        StripTitle("Alben") { NewButton { creating = true } }
        if (vm.userAlbums.isEmpty()) {
            Box(Modifier.padding(horizontal = 13.dp)) { Hint("Sammle Bilder aus allen Ordnern in eigenen Alben. Die Dateien bleiben, wo sie sind.") }
        } else {
            val byId = remember(vm.images) { vm.images.associateBy { it.id } }
            Strip {
                items(vm.userAlbums, key = { it.id }) { a ->
                    val cover = remember(a.ids, byId) { a.ids.firstNotNullOfOrNull { byId[it] } }
                    val count = remember(a.ids, byId) { a.ids.count { it in byId } }
                    StripCard(a.name, countLabel(count), cover, Icons.Rounded.PhotoAlbum) { vm.open = Source.User(a.id) }
                }
            }
        }
        if (vm.loaded && vm.images.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                Text("Noch keine Bilder auf dem Handy", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (creating) {
        NameDialog(title = "Neues Album", initial = "", confirm = "Anlegen", onDismiss = { creating = false }) { name ->
            creating = false
            vm.open = Source.User(vm.createAlbum(name).id)
        }
    }
}

/** Above trunk's library: uploads, memories, trunk's albums and what was sent between people. */
@Composable
private fun TrunkHeader(vm: GalleryViewModel) {
    var creating by remember { mutableStateOf(false) }
    val upload = rememberUploadProgress(vm)
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (upload != null) {
            val (done, total) = upload
            Column(Modifier.padding(horizontal = 13.dp, vertical = 6.dp)) {
                Text(if (total > 0) "Lädt hoch: ${done + 1} von $total" else "Lädt hoch …", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                if (total > 0) {
                    LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                }
            }
        }
        vm.trunkMemories?.let { m ->
            Strip { item(key = "memories") { MemoriesTile(m) { vm.open = Source.Memories(true) } } }
        }
        val albums = vm.trunkAlbums.filter { !it.direct }
        StripTitle("Alben") { NewButton { creating = true } }
        if (albums.isEmpty()) {
            Box(Modifier.padding(horizontal = 13.dp)) { Hint("Alben auf dem Server sehen alle deine Geräte. Wähle Bilder aus und tippe auf „Album“.") }
        } else {
            Strip { items(albums, key = { it.id }) { a -> TrunkStripCard(vm, a) } }
        }
        // Photos sent between people: "Von alice", "An bob".
        val direct = vm.trunkAlbums.filter { it.direct }
        if (direct.isNotEmpty()) {
            StripTitle("Geteilt")
            Strip { items(direct, key = { it.id }) { a -> TrunkStripCard(vm, a) } }
        }
    }
    if (creating) {
        NameDialog(title = "Neues Album auf trunk", initial = "", confirm = "Anlegen", onDismiss = { creating = false }) { name ->
            creating = false
            vm.trunkCreateAlbum(name) { id -> vm.open = Source.TrunkAlbum(id) }
        }
    }
}

@Composable
private fun TrunkStripCard(vm: GalleryViewModel, a: io.github.carnager.peek.trunk.RemoteAlbum) {
    val images = remember(a, vm.trunk, vm.trunkShared) { vm.trunkAlbumImages(a) }
    StripCard(a.title, countLabel(images.size), images.firstOrNull(), Icons.Rounded.PhotoAlbum, shared = a.shared && !a.direct) {
        vm.open = Source.TrunkAlbum(a.id)
    }
}

@Composable
private fun StripTitle(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 14.dp, bottom = 8.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** A row of cards that scrolls sideways. */
@Composable
private fun Strip(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private val StripHeight = 136.dp

@Composable
private fun StripCard(
    title: String,
    subtitle: String,
    cover: MediaImage?,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.primary,
    shared: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 124.dp, height = StripHeight)
            .bounceClick(pressedScale = 0.95f, onClick = onClick)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (cover != null) {
            Thumb(cover, Modifier.fillMaxSize(), size = 300)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))))
        } else {
            Icon(icon, null, tint = tint, modifier = Modifier.align(Alignment.TopStart).padding(14.dp).size(28.dp))
        }
        val fg = if (cover != null) Color.White else MaterialTheme.colorScheme.onSurface
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, color = fg, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        if (shared) {
            Icon(
                Icons.Rounded.Group, "Geteilt", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(4.dp),
            )
        }
    }
}

/** "Heute vor 2 Jahren": a wider card with a photo from back then, shown only on days that have one. */
@Composable
private fun MemoriesTile(m: OnThisDay, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 220.dp, height = StripHeight)
            .bounceClick(pressedScale = 0.95f, onClick = onClick)
            .clip(RoundedCornerShape(22.dp)),
    ) {
        Thumb(m.cover, Modifier.fillMaxSize(), size = 600)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("Rückblick", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
            Text(m.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(m.place, countLabel(m.images.size)).joinToString(" · "),
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp)) {
        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Neu")
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
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
private fun AlbumCard(name: String, count: Int, cover: MediaImage, modifier: Modifier = Modifier, aspect: Float = 1f, shared: Boolean = false, onClick: () -> Unit) {
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
        if (shared) {
            Icon(
                Icons.Rounded.Group, "Geteilt", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(5.dp),
            )
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
