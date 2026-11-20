package io.github.carnager.peek.viewer

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
    val progress = (abs(drag.value) / (dismissPx * 2.5f)).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - progress))
            .draggable(
                state = rememberDraggableState { d -> if (!zoomed) scope.launch { drag.snapTo(drag.value + d) } },
                orientation = Orientation.Vertical,
                enabled = !zoomed,
                onDragStarted = { chrome = false },
                onDragStopped = { velocity ->
                    if (abs(drag.value) > dismissPx || abs(velocity) > 2500f) {
                        val p = (abs(drag.value) / (dismissPx * 2.5f)).coerceIn(0f, 1f)
                        onSwipeClose?.invoke(drag.value, 1f - p * 0.25f, 1f - p) ?: onClose()
                    } else {
                        chrome = true
                        drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
                    }
                },
            )
    ) {
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
            val state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 10f)))
            if (page == pager.currentPage) {
                val z = (state.zoomableState.zoomFraction ?: 0f) > 0.01f
                LaunchedEffect(z) { zoomed = z }
            }
            Box(Modifier.fillMaxSize()) {
                ZoomableAsyncImage(
                    model = coil3.request.ImageRequest.Builder(ctx)
                        .data(item.uri)
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
                GlassButton(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück", onClose)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(current.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    if (items.size > 1) {
                        Text("${pager.currentPage + 1} von ${items.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Box {
                    GlassButton(Icons.Rounded.MoreVert, "Mehr") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                        DropdownMenuItem(
                            text = { Text("Öffnen mit") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                            onClick = { menu = false; openWith(ctx, current) },
                        )
                        DropdownMenuItem(
                            text = { Text("Festlegen als") },
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
                    PillIcon(Icons.Rounded.Share, "Teilen") { share(ctx, listOf(current.uri), current.mimeType) }
                    PillIcon(Icons.Rounded.AutoFixHigh, "Bearbeiten", highlight = true) { onEdit(current) }
                    if (onDelete != null) PillIcon(Icons.Rounded.DeleteOutline, "Löschen") { onDelete(current) }
                    PillIcon(Icons.Rounded.Info, "Info") { info = current }
                }
            }
        }
    }

    info?.let { item ->
        ModalBottomSheet(onDismissRequest = { info = null }) {
            InfoSheet(item)
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

@Composable
private fun InfoSheet(item: ViewerItem) {
    val ctx = LocalContext.current
    val exif by produceState<List<Pair<String, String>>>(emptyList(), item.uri) {
        value = withContext(Dispatchers.IO) { readExifSummary(ctx, item.uri) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 16.dp))
        val rows = buildList {
            if (item.date > 0) add(Triple(Icons.Rounded.CalendarMonth, "Datum", DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(item.date))))
            if (item.width > 0) add(Triple(Icons.Rounded.AspectRatio, "Auflösung", "${item.width} × ${item.height} · ${"%.1f".format(item.width * item.height / 1e6)} MP"))
            if (item.size > 0) add(Triple(Icons.Rounded.Storage, "Größe", "${Formatter.formatFileSize(ctx, item.size)} · ${item.mimeType.substringAfter('/').uppercase()}"))
            item.path?.let { add(Triple(Icons.Rounded.Folder, "Ordner", it)) }
            exif.forEach { (k, v) -> add(Triple(if (k == "Kamera") Icons.Rounded.PhotoCamera else Icons.Rounded.Camera, k, v)) }
        }
        rows.forEach { (icon, k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp)) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}


private fun readExifSummary(ctx: Context, uri: Uri): List<Pair<String, String>> = runCatching {
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        val e = ExifInterface(input)
        buildList {
            val camera = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL))
                .joinToString(" ").trim()
            if (camera.isNotEmpty()) add("Kamera" to camera)
            val parts = buildList {
                e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { add("f/%.1f".format(it)) }
                e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                    add(if (it < 1) "1/${(1 / it).toInt()} s" else "%.1f s".format(it))
                }
                e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { add("ISO $it") }
                e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { add("%.0f mm".format(it)) }
            }
            if (parts.isNotEmpty()) add("Belichtung" to parts.joinToString(" · "))
        }
    }
}.getOrNull().orEmpty()

fun share(ctx: Context, uris: List<Uri>, mime: String = "image/*") {
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
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
