package io.github.carnager.peek.viewer

import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.MotionPhotosPause
import androidx.compose.material.icons.rounded.MotionPhotosOn
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import io.github.carnager.peek.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import io.github.carnager.peek.ui.bounceClick
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.text.DateFormat
import java.util.Date

data class ViewerItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val date: Long = 0,
    val path: String? = null,
    /** Version tag so an edited file reloads instead of showing the cached old pixels. */
    val version: Long = 0,
    /** Memory-cache key of an already loaded thumbnail, shown until the full image is ready. */
    val placeholderKey: String? = null,
    val favorite: Boolean = false,
    val isVideo: Boolean = false,
    /** Video length in ms. */
    val duration: Long = 0,
    /** Lives on the trunk server: metadata comes from the server. */
    val remote: Boolean = false,
    /** Edited on trunk, so the edit can be dropped again. */
    val edited: Boolean = false,
    /** Someone else's photo, seen in a shared album: to look at and share, not to change. */
    val owner: String? = null,
    /** A screen-sized rendition to show instead of loading [uri] in full. */
    val previewUrl: String? = null,
    /** Metadata already known (from the server), so the info sheet need not read the file. */
    val meta: PhotoMeta? = null,
    /** A motion photo on trunk: its video. Photos on the phone are looked at in the viewer. */
    val motionUrl: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewer(
    items: List<ViewerItem>,
    startIndex: Int,
    onClose: () -> Unit,
    onEdit: (ViewerItem) -> Unit,
    onDelete: ((ViewerItem) -> Unit)?,
    onPageChange: (Int) -> Unit = {},
    /** Called instead of [onClose] after a swipe-away, with the photo's offset, scale and backdrop opacity. */
    onSwipeClose: ((offsetY: Float, scale: Float, backdrop: Float) -> Unit)? = null,
    onFavorite: ((ViewerItem) -> Unit)? = null,
    onAddToAlbum: ((ViewerItem) -> Unit)? = null,
    /** trunk: send to someone in the household, or make a public link. Only for our own photos. */
    onSend: ((ViewerItem) -> Unit)? = null,
    onLink: ((ViewerItem) -> Unit)? = null,
    /** Replaces the built-in share, e.g. to fetch a server original first. */
    onShare: ((ViewerItem) -> Unit)? = null,
    /** Offered for [ViewerItem.edited] items: go back to the first upload. */
    onRevertEdit: ((ViewerItem) -> Unit)? = null,
    /** Corrects date and place (trunk photos). */
    onAdjust: ((ViewerItem) -> Unit)? = null,
    /** Saves a caption; null where captions cannot be stored (phone photos). */
    onCaption: ((ViewerItem, String) -> Unit)? = null,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<ViewerItem?>(null) }
    var menu by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    ImmersiveBars(visible = chrome)
    LaunchedEffect(pager.currentPage) { onPageChange(pager.currentPage) }

    // Swipe down (or up) on an unzoomed photo to close the viewer.
    val drag = remember { Animatable(0f) }
    var zoomed by remember { mutableStateOf(false) }
    val dismissPx = with(LocalDensity.current) { 140.dp.toPx() }
    // Only pulling down shrinks and fades towards closing; pulling up is the info gesture.
    val progress = (drag.value.coerceAtLeast(0f) / (dismissPx * 2.5f)).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - progress))
            .draggable(
                state = rememberDraggableState { d ->
                    if (zoomed) return@rememberDraggableState
                    // Upwards the photo moves at half speed, like something with a bit of weight.
                    val step = if (drag.value + d < 0f) d * 0.5f else d
                    scope.launch { drag.snapTo(drag.value + step) }
                },
                orientation = Orientation.Vertical,
                enabled = !zoomed,
                onDragStarted = { chrome = false },
                onDragStopped = { velocity ->
                    val y = drag.value
                    when {
                        // Down: close.
                        y > dismissPx || (y > 0f && velocity > 2500f) -> {
                            val p = (y / (dismissPx * 2.5f)).coerceIn(0f, 1f)
                            onSwipeClose?.invoke(y, 1f - p * 0.25f, 1f - p) ?: onClose()
                        }
                        // Up: show the info and let the photo settle back.
                        y < -dismissPx * 0.35f || (y < 0f && velocity < -2000f) -> {
                            info = items[pager.currentPage.coerceIn(0, items.lastIndex)]
                            chrome = true
                            drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                        }
                        else -> {
                            chrome = true
                            drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
                        }
                    }
                },
            )
    ) {
        // Motion photos: the video of the photo on screen, while it plays.
        var motionPlaying by remember { mutableStateOf<android.net.Uri?>(null) }
        val shownItem = items[pager.currentPage.coerceIn(0, items.lastIndex)]
        LaunchedEffect(pager.currentPage) { motionPlaying = null }
        // Where its video is: trunk says so; a photo on the phone is looked at (its header only).
        val motionLocal by produceState<LongArray?>(null, shownItem.uri, shownItem.version) {
            value = if (!shownItem.remote && !shownItem.isVideo && shownItem.mimeType == "image/jpeg") {
                MotionPhotos.find(ctx, shownItem.uri, shownItem.version)
            } else null
        }
        val hasMotion = shownItem.motionUrl != null || motionLocal != null
        val playMotion: () -> Unit = {
            val local = motionLocal
            if (shownItem.motionUrl != null) {
                motionPlaying = android.net.Uri.parse(shownItem.motionUrl)
            } else if (local != null) {
                scope.launch { motionPlaying = MotionPhotos.extract(ctx, shownItem.uri, shownItem.version, local) }
            }
        }
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { "${items[it].uri}#${items[it].version}" },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationY = drag.value
                val s = 1f - progress * 0.25f
                scaleX = s
                scaleY = s
            },
        ) { page ->
            val item = items[page]
            if (item.isVideo) {
                if (page == pager.currentPage) LaunchedEffect(Unit) { zoomed = false }
                VideoPage(item, isCurrent = page == pager.currentPage, chrome = chrome, onTap = { chrome = !chrome })
                return@HorizontalPager
            }
            val state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 10f)))
            if (page == pager.currentPage) {
                val z = (state.zoomableState.zoomFraction ?: 0f) > 0.01f
                LaunchedEffect(z) { zoomed = z }
            }
            Box(Modifier.fillMaxSize()) {
                ZoomableAsyncImage(
                    model = coil3.request.ImageRequest.Builder(ctx)
                        .data(item.previewUrl ?: item.uri)
                        .memoryCacheKey("${item.uri}#${item.version}")
                        .placeholderMemoryCacheKey(item.placeholderKey)
                        .build(),
                    contentDescription = item.name,
                    state = state,
                    onClick = { chrome = !chrome },
                    onDoubleClick = DoubleClickToZoomListener.cycle(maxZoomFactor = 3f),
                    modifier = Modifier.fillMaxSize(),
                )
                if (!state.isImageDisplayed) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(36.dp))
                }
                val playing = motionPlaying
                if (page == pager.currentPage && playing != null) {
                    MotionOverlay(playing) { motionPlaying = null }
                }
            }
        }

        val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                GlassButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.view_back), onClose)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(current.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    if (items.size > 1) {
                        Text(stringResource(R.string.view_position, pager.currentPage + 1, items.size), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (hasMotion) {
                    GlassButton(
                        if (motionPlaying != null) Icons.Rounded.MotionPhotosPause else Icons.Rounded.MotionPhotosOn,
                        stringResource(R.string.view_motion),
                    ) { if (motionPlaying != null) motionPlaying = null else playMotion() }
                    Spacer(Modifier.width(8.dp))
                }
                Box {
                    GlassButton(Icons.Rounded.MoreVert, stringResource(R.string.view_more)) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.view_open_with)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                            onClick = { menu = false; openWith(ctx, current) },
                        )
                        if (onSend != null && current.owner == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.view_send_to_person)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Send, null) },
                                onClick = { menu = false; onSend(current) },
                            )
                        }
                        if (onLink != null && current.owner == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.view_create_link)) },
                                leadingIcon = { Icon(Icons.Rounded.Link, null) },
                                onClick = { menu = false; onLink(current) },
                            )
                        }
                        if (onAddToAlbum != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.view_add_to_album)) },
                                leadingIcon = { Icon(Icons.Rounded.PhotoAlbum, null) },
                                onClick = { menu = false; onAddToAlbum(current) },
                            )
                        }
                        if (onAdjust != null && current.remote && current.owner == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.view_adjust)) },
                                leadingIcon = { Icon(Icons.Rounded.EditCalendar, null) },
                                onClick = { menu = false; onAdjust(current) },
                            )
                        }
                        if (onRevertEdit != null && current.edited && current.owner == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.view_revert_edit)) },
                                leadingIcon = { Icon(Icons.Rounded.Restore, null) },
                                onClick = { menu = false; onRevertEdit(current) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.view_set_as)) },
                            leadingIcon = { Icon(Icons.Rounded.Wallpaper, null) },
                            onClick = { menu = false; setAs(ctx, current) },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn() + slideInVertically(spring(dampingRatio = 0.75f)) { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(Modifier.navigationBarsPadding().padding(bottom = 20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF1C1C1E).copy(alpha = 0.82f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    PillIcon(Icons.Rounded.Share, stringResource(R.string.view_share)) { onShare?.invoke(current) ?: share(ctx, listOf(current.uri), current.mimeType) }
                    if (onFavorite != null && current.owner == null) {
                        FavoriteIcon(current.favorite) { onFavorite(current) }
                    }
                    if (!current.isVideo && current.owner == null) PillIcon(Icons.Rounded.AutoFixHigh, stringResource(R.string.view_edit), highlight = true) { onEdit(current) }
                    if (onDelete != null && current.owner == null) PillIcon(Icons.Rounded.DeleteOutline, stringResource(R.string.view_delete)) { onDelete(current) }
                    PillIcon(Icons.Rounded.Info, stringResource(R.string.view_info)) { info = current }
                }
            }
        }
    }

    info?.let { item ->
        // Always fully open, so one back gesture closes it instead of first halving it.
        ModalBottomSheet(onDismissRequest = { info = null }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            // The live item, so a caption just saved shows at once.
            val live = items.firstOrNull { it.uri == item.uri } ?: item
            InfoSheet(live, onCaption = onCaption?.takeIf { live.remote && live.owner == null }?.let { save -> { c: String -> save(live, c) } })
        }
    }
}

@Composable
private fun GlassButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White)
    }
}

/** A heart that pops when it turns on. */
@Composable
private fun FavoriteIcon(on: Boolean, onClick: () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    var last by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (on && !last) {
            scale.snapTo(0.6f)
            scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
        }
        last = on
    }
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(width = 60.dp, height = 48.dp)
            .clip(CircleShape)
            .bounceClick(pressedScale = 0.85f) {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            stringResource(if (on) R.string.view_favorite_remove else R.string.view_favorite_add),
            tint = if (on) Color(0xFFFF4D6D) else Color.White,
            modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
    }
}

@Composable
private fun PillIcon(icon: ImageVector, label: String, highlight: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(width = 60.dp, height = 48.dp)
            .clip(CircleShape)
            .background(if (highlight) MaterialTheme.colorScheme.primary else Color.Transparent)
            .bounceClick(pressedScale = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (highlight) MaterialTheme.colorScheme.onPrimary else Color.White)
    }
}

fun share(ctx: Context, uris: List<Uri>, mime: String = "image/*") {
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    // Read permission for every file, also our own FileProvider ones, travels with the ClipData.
    intent.clipData = android.content.ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, null))
}

private fun openWith(ctx: Context, item: ViewerItem) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, null))
}

private fun setAs(ctx: Context, item: ViewerItem) {
    val intent = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(item.uri, item.mimeType)
        .putExtra("mimeType", item.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, null))
}

/** Hides status and navigation bars while the viewer's own bars are hidden, and restores them on exit. */
@Composable
private fun ImmersiveBars(visible: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(controller) {
        val lightBefore = controller.isAppearanceLightStatusBars
        val lightNavBefore = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightBefore
            controller.isAppearanceLightNavigationBars = lightNavBefore
        }
    }
    LaunchedEffect(visible) {
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
