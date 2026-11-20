package io.github.carnager.peek.review

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
                add("Durchgesehen")
                if (gone > 0) add("$gone gelöscht")
                if (up > 0) add("$up gehen zu trunk")
            }.joinToString(" · "),
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { vm.review = null }) { Icon(Icons.Rounded.Close, "Später") } },
                    title = {
                        Column {
                            Text("Durchsehen")
                            Text(
                                when {
                                    step == 1 && twoSteps -> "Schritt 1 von 2: Was bleibt?"
                                    step == 1 -> "Was bleibt?"
                                    else -> "Schritt 2 von 2: Was geht zu trunk?"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (step == 2) {
                            TextButton(onClick = { upload = if (upload.size == kept.size) emptySet() else kept.mapTo(HashSet()) { it.id } }) {
                                Text(if (upload.size == kept.size) "Keins" else "Alle")
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
                                if (discard.isEmpty()) "Tippe an, was weg soll" else "${discard.size} löschen · ${kept.size} behalten",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = {
                                val gone = session.items.filter { it.id in discard }
                                if (gone.isNotEmpty()) ops.trash(gone)
                                if (twoSteps && kept.isNotEmpty()) step = 2 else finish()
                            }) { Text(if (twoSteps && kept.isNotEmpty()) "Weiter" else "Fertig") }
                        } else {
                            OutlinedButton(onClick = { upload = emptySet(); finish() }, modifier = Modifier.weight(1f)) { Text("Nichts hochladen") }
                            Button(onClick = ::finish, enabled = upload.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                Text(if (upload.isEmpty()) "Hochladen" else "${upload.size} hochladen")
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
                                .then(if (s == 2 && marked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
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
                                    Icon(Icons.Rounded.DeleteOutline, "Wird gelöscht", tint = Color.White, modifier = Modifier.size(36.dp))
                                }
                            }
                            if (s == 2 && marked) {
                                Icon(
                                    Icons.Rounded.CheckCircle, "Geht zu trunk",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(Color.White, CircleShape),
                                )
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

/** The manual way into the review, on the album overview; also leads to its settings. */
@Composable
fun ReviewCard(vm: GalleryViewModel, onSettings: () -> Unit) {
    val s = vm.reviewSettings
    val pending = vm.reviewPending.size
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .bounceClick(pressedScale = 0.97f) { if (!s.configured) onSettings() else vm.startReview() }
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Today, null, tint = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("Neue Bilder durchsehen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    !s.configured -> "Behalten, löschen, zu trunk: einrichten"
                    pending == 0 -> "Nichts Neues seit dem letzten Mal"
                    pending == 1 -> "1 neues Bild"
                    else -> "$pending neue Bilder"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Einstellungen") }
    }
}

/** Which folders count, and whether and when to be reminded. Applied when the sheet closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSettingsSheet(vm: GalleryViewModel, ops: MediaOps, onDone: () -> Unit) {
    val initial = remember { vm.reviewSettings }
    val camera = remember(vm.folders) { vm.folders.firstOrNull { it.cover.relativePath?.startsWith("DCIM/Camera", ignoreCase = true) == true }?.bucketId }
    var s by remember { mutableStateOf(if (initial.configured) initial else initial.copy(buckets = setOfNotNull(camera))) }
    var pickTime by remember { mutableStateOf(false) }
    val close = {
        if (s != initial || !initial.configured) vm.saveReviewSettings(s)
        onDone()
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Neue Bilder durchsehen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Geh durch, was seit dem letzten Mal dazugekommen ist: erst was bleibt, dann was zu trunk geht. Ohne dich wird nichts gelöscht oder hochgeladen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).bounceClick(pressedScale = 0.98f) {
                    if (s.remind) s = s.copy(remind = false)
                    else ops.requestNotifications { ok ->
                        if (ok) s = s.copy(remind = true) else vm.message = Message("Ohne Benachrichtigungen kann peek nicht erinnern.")
                    }
                }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Abends erinnern", style = MaterialTheme.typography.bodyLarge)
                    Text("Nur wenn es Neues gibt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.remind, onCheckedChange = null)
            }
            if (s.remind) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).bounceClick(pressedScale = 0.98f) { pickTime = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Uhrzeit", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("%02d:%02d".format(s.hour, s.minute), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text("Ordner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(vm.folders, key = { it.bucketId }) { f ->
                    val on = f.bucketId in s.buckets
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).bounceClick(pressedScale = 0.98f) {
                            s = s.copy(buckets = if (on) s.buckets - f.bucketId else s.buckets + f.bucketId)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${f.count}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (pickTime) {
        val state = rememberTimePickerState(initialHour = s.hour, initialMinute = s.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    s = s.copy(hour = state.hour, minute = state.minute)
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Abbrechen") } },
            text = { TimeInput(state) },
        )
    }
}
