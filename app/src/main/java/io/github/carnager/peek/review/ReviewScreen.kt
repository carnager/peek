package io.github.carnager.peek.review

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.carnager.peek.R
import io.github.carnager.peek.gallery.GalleryViewModel
import io.github.carnager.peek.gallery.MediaImage
import io.github.carnager.peek.gallery.MediaOps
import io.github.carnager.peek.gallery.Message
import io.github.carnager.peek.gallery.Thumb
import io.github.carnager.peek.ui.bounceClick

/**
 * The review itself. Step 1: tap what should go; it moves to the phone's trash. Step 2 (when
 * connected to trunk): tap what should go to trunk; nothing is preselected. A long press shows a
 * photo full size. Closing before the end leaves everything as it was, to be reviewed next time.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReviewScreen(vm: GalleryViewModel, ops: MediaOps, session: GalleryViewModel.ReviewSession) {
    var step by remember { mutableIntStateOf(1) }
    var discard by remember { mutableStateOf(setOf<Long>()) }
    var upload by remember { mutableStateOf(setOf<Long>()) }
    var preview by remember { mutableStateOf<MediaImage?>(null) }
    val kept = remember(discard) { session.items.filter { it.id !in discard } }
    val twoSteps = vm.trunkConnected
    val ctx = LocalContext.current
    BackHandler {
        when {
            preview != null -> preview = null
            step == 2 -> step = 1
            else -> vm.review = null
        }
    }

    fun finish() {
        if (step == 2 && upload.isNotEmpty()) ops.uploadToTrunk(kept.filter { it.id in upload })
        val gone = discard.size
        val up = if (step == 2) upload.size else 0
        vm.finishReview(session)
        vm.message = Message(
            buildList {
                add(ctx.getString(R.string.review_done))
                if (gone > 0) add(ctx.getString(R.string.review_done_deleted, gone))
                if (up > 0) add(ctx.resources.getQuantityString(R.plurals.review_done_uploading, up, up))
            }.joinToString(" · "),
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { vm.review = null }) { Icon(Icons.Rounded.Close, stringResource(R.string.review_later)) } },
                    title = {
                        Column {
                            Text(stringResource(R.string.review_title))
                            Text(
                                when {
                                    step == 1 && twoSteps -> stringResource(R.string.review_step1_of2)
                                    step == 1 -> stringResource(R.string.review_step1)
                                    else -> stringResource(R.string.review_step2_of2)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (step == 2) {
                            TextButton(onClick = { upload = if (upload.size == kept.size) emptySet() else kept.mapTo(HashSet()) { it.id } }) {
                                Text(if (upload.size == kept.size) stringResource(R.string.review_select_none) else stringResource(R.string.review_select_all))
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (step == 1) {
                            Text(
                                if (discard.isEmpty()) stringResource(R.string.review_tap_hint) else stringResource(R.string.review_delete_keep, discard.size, kept.size),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = {
                                val gone = session.items.filter { it.id in discard }
                                if (gone.isNotEmpty()) ops.trash(gone)
                                if (twoSteps && kept.isNotEmpty()) step = 2 else finish()
                            }) { Text(if (twoSteps && kept.isNotEmpty()) stringResource(R.string.review_next) else stringResource(R.string.review_finish)) }
                        } else {
                            OutlinedButton(onClick = { upload = emptySet(); finish() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.review_upload_nothing)) }
                            Button(onClick = ::finish, enabled = upload.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                Text(if (upload.isEmpty()) stringResource(R.string.review_upload) else stringResource(R.string.review_upload_count, upload.size))
                            }
                        }
                    }
                }
            },
        ) { pad ->
            AnimatedContent(step, label = "step", modifier = Modifier.padding(pad)) { s ->
                val list = if (s == 1) session.items else kept
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.id }) { img ->
                        val marked = if (s == 1) img.id in discard else img.id in upload
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .combinedClickable(
                                    onClick = {
                                        if (s == 1) discard = if (marked) discard - img.id else discard + img.id
                                        else upload = if (marked) upload - img.id else upload + img.id
                                    },
                                    onLongClick = { preview = img },
                                ),
                        ) {
                            Thumb(img, Modifier.fillMaxSize(), size = 300)
                            if (img.isVideo) {
                                Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp))
                            }
                            if (s == 1 && marked) {
                                Box(Modifier.fillMaxSize().background(Color(0xCC8B1A1A)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.review_marked_delete), tint = Color.White, modifier = Modifier.size(36.dp))
                                }
                            }
                            if (s == 2 && marked) {
                                Box(Modifier.fillMaxSize().background(Color(0xCC1A4F8B)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.CloudUpload, stringResource(R.string.review_marked_upload), tint = Color.White, modifier = Modifier.size(36.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { img ->
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { preview = null }, contentAlignment = Alignment.Center) {
            AsyncImage(model = img.uri, contentDescription = img.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

