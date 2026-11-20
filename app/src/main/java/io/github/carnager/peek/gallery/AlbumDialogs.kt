package io.github.carnager.peek.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.carnager.peek.ui.bounceClick

/**
 * Bottom sheet to put [ids] into one of the user's albums, or a new one. Server photos (negative
 * ids, see GalleryViewModel.toImage) go into albums on trunk, phone photos into peek's own.
 */
@Composable
fun AddToAlbumSheet(vm: GalleryViewModel, ids: List<Long>, onDone: () -> Unit) {
    if (ids.isNotEmpty() && ids.all { it < 0 }) TrunkAlbumSheet(vm, ids, onDone) else LocalAlbumSheet(vm, ids, onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrunkAlbumSheet(vm: GalleryViewModel, ids: List<Long>, onDone: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val remoteIds = remember(ids, vm.trunk) { val byId = vm.trunk.associateBy { it.id }; ids.mapNotNull { byId[it]?.remote?.id } }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                if (ids.size == 1) "Zu Album auf trunk hinzufügen" else "${ids.size} Bilder zu Album auf trunk hinzufügen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { NewAlbumRow { creating = true } }
                // Albums we may add to: our own, and shared ones that let us.
                items(vm.trunkAlbums.filter { it.canAdd }, key = { it.id }) { a ->
                    val cover = remember(a, vm.trunk, vm.trunkShared) { vm.trunkCoverOf(a) }
                    val already = remoteIds.all { it in a.assetIds }
                    AlbumRow(
                        title = a.name,
                        subtitle = if (already) "Schon enthalten" else listOfNotNull(a.owner?.let { "von $it" }, "${a.assetIds.size} Bilder").joinToString(" · "),
                        leading = { AlbumThumb(cover) },
                        trailing = if (already) ({ Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    ) {
                        vm.trunkAddTo(a.id, ids)
                        onDone()
                    }
                }
            }
        }
    }
    if (creating) {
        NameDialog(title = "Neues Album auf trunk", initial = "", confirm = "Anlegen", onDismiss = { creating = false }) { name ->
            vm.trunkCreateAlbum(name, ids)
            creating = false
            onDone()
        }
    }
}

@Composable
private fun NewAlbumRow(onClick: () -> Unit) {
    AlbumRow(
        title = "Neues Album",
        subtitle = null,
        leading = {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        },
        onClick = onClick,
    )
}

@Composable
private fun AlbumThumb(cover: MediaImage?) {
    if (cover != null) {
        Thumb(cover, Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)), size = 200)
    } else {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PhotoAlbum, null) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalAlbumSheet(vm: GalleryViewModel, ids: List<Long>, onDone: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val byId = remember(vm.images) { vm.images.associateBy { it.id } }
    // Always fully open, so one back gesture closes it instead of first halving it.
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                if (ids.size == 1) "Zu Album hinzufügen" else "${ids.size} Bilder zu Album hinzufügen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { NewAlbumRow { creating = true } }
                items(vm.userAlbums, key = { it.id }) { a ->
                    val cover = remember(a.ids, byId) { a.ids.firstNotNullOfOrNull { byId[it] } }
                    val already = ids.all { it in a.ids }
                    AlbumRow(
                        title = a.name,
                        subtitle = if (already) "Schon enthalten" else "${a.ids.count { it in byId }} Bilder",
                        leading = { AlbumThumb(cover) },
                        trailing = if (already) ({ Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    ) {
                        vm.addTo(a.id, ids)
                        onDone()
                    }
                }
            }
        }
    }
    if (creating) {
        NameDialog(title = "Neues Album", initial = "", confirm = "Anlegen", onDismiss = { creating = false }) { name ->
            val a = vm.createAlbum(name, ids)
            vm.message = Message("${ids.size} zu „${a.name}“ hinzugefügt")
            creating = false
            onDone()
        }
    }
}

@Composable
private fun AlbumRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().bounceClick(pressedScale = 0.97f, onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

/** Asks for an album name. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val ok = name.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Name") },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(name.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { Button(onClick = { onConfirm(name.text) }, enabled = ok) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * Shares a trunk album within the household: everyone with an account on the server is listed;
 * a tick shares, and each member either only looks or may add photos of their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareAlbumSheet(vm: GalleryViewModel, album: io.github.carnager.peek.trunk.RemoteAlbum, onDone: () -> Unit) {
    var people by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            people = vm.household()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    // The live album, so ticks follow what the server says.
    val current = vm.trunkAlbums.firstOrNull { it.id == album.id } ?: album
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("„${current.name}“ teilen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Mit den anderen Konten auf deinem trunk. Sie sehen das Album; mit „Hinzufügen“ können sie eigene Fotos dazulegen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            val list = people
            when {
                failed -> Text("trunk nicht erreichbar", color = MaterialTheme.colorScheme.error)
                list == null -> CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                list.isEmpty() -> Text(
                    "Auf diesem trunk gibt es noch keine anderen Konten. Anlegen mit: trunk user add NAME",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> list.forEach { (id, name) ->
                    val m = current.members.firstOrNull { it.id == id }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = m != null, onCheckedChange = { on -> if (on) vm.trunkShare(current.id, id, true) else vm.trunkUnshare(current.id, id) })
                        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        if (m != null) {
                            SingleChoiceSegmentedButtonRow {
                                SegmentedButton(
                                    selected = !m.canAdd, onClick = { if (m.canAdd) vm.trunkShare(current.id, id, false) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Ansehen") },
                                )
                                SegmentedButton(
                                    selected = m.canAdd, onClick = { if (!m.canAdd) vm.trunkShare(current.id, id, true) },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Hinzufügen") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
