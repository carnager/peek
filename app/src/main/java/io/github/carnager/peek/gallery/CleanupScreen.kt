package io.github.carnager.peek.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesomeMosaic
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.carnager.peek.R
import java.text.DateFormat

/**
 * Look-alikes on trunk: one picture in several files, and bursts. Each group starts with trunk's
 * suggestion (keep the best, delete the rest); tapping a photo switches it. A long press shows it
 * full size. What is deleted goes to trunk's trash, so it can be undone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CleanupScreen(vm: GalleryViewModel, onClose: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadSimilar() }
    var preview by remember { mutableStateOf<MediaImage?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    // What to delete per group; until touched, the suggestion.
    val marks = remember { mutableStateMapOf<GalleryViewModel.SimilarGroup, Set<Long>>() }
    fun marked(g: GalleryViewModel.SimilarGroup, images: List<MediaImage>): Set<Long> =
        marks[g] ?: images.mapNotNull { it.remote?.id }.filterTo(HashSet()) { it != g.keep }
    BackHandler { if (preview != null) preview = null else onClose() }

    val groups = vm.similar
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.gallery_back)) } },
                title = { Text(stringResource(R.string.gallery_cleanup)) },
                actions = {
                    if (!groups.isNullOrEmpty()) {
                        TextButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.gallery_cleanup_all)) }
                    }
                },
            )
        },
    ) { pad ->
        when {
            groups == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            groups.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(pad).padding(40.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.AutoAwesomeMosaic, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    vm.similarError ?: stringResource(R.string.gallery_cleanup_empty_title),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                )
                if (vm.similarError == null) {
                    Text(
                        stringResource(R.string.gallery_cleanup_empty_text),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (vm.similarPending > 0) PendingNote(vm.similarPending)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 96.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "hint") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.gallery_cleanup_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (vm.similarPending > 0) PendingNote(vm.similarPending)
                    }
                }
                items(groups, key = { it.key }) { g ->
                    val images = remember(g, vm.trunk) { vm.similarImages(g) }
                    if (images.size < 2) return@items // the library has not caught up yet
                    val del = marked(g, images)
                    GroupCard(
                        g, images, del,
                        onToggle = { id -> marks[g] = if (id in del) del - id else del + id },
                        onPreview = { preview = it },
                        onNotSimilar = { vm.notSimilar(g) },
                        onDelete = { vm.cleanUp(mapOf(g to del)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (confirmAll && groups != null) {
        val all = groups.associateWith { g -> marked(g, vm.similarImages(g)) }.filterValues { it.isNotEmpty() }
        val n = all.values.sumOf { it.size }
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.gallery_cleanup_all_title)) },
            text = { Text(pluralStringResource(R.plurals.gallery_cleanup_all_text, n, n)) },
            confirmButton = {
                Button(onClick = { confirmAll = false; vm.cleanUp(all) }, enabled = n > 0) {
                    Text(pluralStringResource(R.plurals.gallery_cleanup_delete, n, n))
                }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.gallery_cancel)) } },
        )
    }

    preview?.let { img ->
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { preview = null }, contentAlignment = Alignment.Center) {
            AsyncImage(model = img.uri, contentDescription = img.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PendingNote(n: Int) {
    Text(
        pluralStringResource(R.plurals.gallery_cleanup_pending, n, n),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(
    g: GalleryViewModel.SimilarGroup,
    images: List<MediaImage>,
    del: Set<Long>,
    onToggle: (Long) -> Unit,
    onPreview: (MediaImage) -> Unit,
    onNotSimilar: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                when (g.kind) {
                    "burst" -> pluralStringResource(R.plurals.gallery_cleanup_burst, images.size, images.size)
                    "motion" -> stringResource(R.string.gallery_cleanup_motion)
                    else -> pluralStringResource(R.plurals.gallery_cleanup_same, images.size, images.size)
                },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            if (g.kind == "motion") {
                Text(
                    stringResource(R.string.gallery_cleanup_motion_text),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(images.first().dateTaken),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(images, key = { it.id }) { img ->
                val id = img.remote?.id ?: 0
                val gone = id in del
                val ratio = if (img.width > 0 && img.height > 0) (img.width.toFloat() / img.height).coerceIn(0.5f, 2f) else 1f
                Box(
                    Modifier
                        .height(150.dp)
                        .aspectRatio(ratio)
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (!gone) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                        .combinedClickable(onClick = { onToggle(id) }, onLongClick = { onPreview(img) }),
                ) {
                    Thumb(img, Modifier.fillMaxSize(), size = 300)
                    if (img.isVideo) {
                        Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp))
                    }
                    if (gone) {
                        // Light enough to still compare the photo with the one kept.
                        Box(Modifier.fillMaxSize().background(Color(0x558B1A1A))) {
                            Icon(
                                Icons.Rounded.DeleteOutline, stringResource(R.string.gallery_delete), tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp).background(Color(0xCC8B1A1A), CircleShape).padding(4.dp),
                            )
                        }
                    } else {
                        Icon(
                            Icons.Rounded.CheckCircle, stringResource(R.string.gallery_cleanup_keep),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(Color.White, CircleShape),
                        )
                    }
                    // Size helps choosing between copies.
                    Text(
                        "${img.width}×${img.height}",
                        color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onNotSimilar) { Text(stringResource(R.string.gallery_cleanup_not_similar)) }
            Button(onClick = onDelete, enabled = del.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.gallery_cleanup_delete, del.size, del.size))
            }
        }
    }
}
