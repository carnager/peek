package io.github.carnager.peek.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.carnager.peek.BuildConfig
import io.github.carnager.peek.gallery.GalleryViewModel
import io.github.carnager.peek.gallery.MediaOps
import io.github.carnager.peek.gallery.Message
import io.github.carnager.peek.gallery.TrunkConnectDialog
import io.github.carnager.peek.gallery.UploadLimitDialog
import io.github.carnager.peek.gallery.uploadLimitLabel
import io.github.carnager.peek.review.Review
import io.github.carnager.peek.trunk.TrunkAccount
import io.github.carnager.peek.ui.bounceClick

/** peek's settings: the nightly review and the trunk connection. Changes apply at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: GalleryViewModel, ops: MediaOps, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val camera = remember(vm.folders) { vm.folders.firstOrNull { it.cover.relativePath?.startsWith("DCIM/Camera", ignoreCase = true) == true }?.bucketId }
    // Until the review is set up, it would start with the camera folder.
    val review = vm.reviewSettings.let { if (it.configured) it else it.copy(buckets = setOfNotNull(camera)) }
    fun saveReview(s: Review.Settings) = vm.saveReviewSettings(s)
    var pickTime by remember { mutableStateOf(false) }
    var pickFolders by remember { mutableStateOf(false) }
    var uploadLimit by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var limitLabel by remember { mutableStateOf(uploadLimitLabel()) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
                    title = { Text("Einstellungen") },
                )
            },
        ) { pad ->
            LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp)) {
                item { Section("Neue Bilder durchsehen") }
                item {
                    SwitchRow("Abends erinnern", "Nur wenn es Neues gibt", review.remind) { on ->
                        if (!on) saveReview(review.copy(remind = false))
                        else ops.requestNotifications { ok ->
                            if (ok) saveReview(review.copy(remind = true)) else vm.message = Message("Ohne Benachrichtigungen kann peek nicht erinnern.")
                        }
                    }
                }
                item {
                    Row("Uhrzeit", "%02d:%02d".format(review.hour, review.minute), enabled = review.remind) { pickTime = true }
                }
                item {
                    val names = vm.folders.filter { it.bucketId in review.buckets }.map { it.name }
                    Row("Ordner", when {
                        names.isEmpty() -> "Keiner gewählt"
                        names.size <= 3 -> names.joinToString(", ")
                        else -> "${names.size} Ordner"
                    }) { pickFolders = true }
                }
                item {
                    val n = vm.reviewPending.size
                    Row("Jetzt durchsehen", if (!vm.reviewSettings.configured) "Behalten, löschen, zu trunk" else if (n == 0) "Nichts Neues" else "$n neue Bilder") {
                        if (!vm.reviewSettings.configured) saveReview(review)
                        onClose()
                        vm.startReview()
                    }
                }

                item { Section("trunk") }
                if (vm.trunkConnected) {
                    item { Row("Server", "${TrunkAccount.user} auf ${TrunkAccount.baseUrl?.substringAfter("://")}") }
                    item { Row("Upload-Größe", limitLabel) { uploadLimit = true } }
                    item { Row("Abmelden", "Dieses Gerät von trunk abmelden", tint = MaterialTheme.colorScheme.error) { vm.trunkLogout() } }
                } else {
                    item { Row("Mit trunk verbinden", "Fotos sichern und auf allen Geräten sehen") { connecting = true } }
                }

                item { Section("Über") }
                item { Row("peek", "Version ${BuildConfig.VERSION_NAME}") }
            }
        }
    }

    if (pickTime) {
        val state = rememberTimePickerState(initialHour = review.hour, initialMinute = review.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    saveReview(review.copy(hour = state.hour, minute = state.minute))
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Abbrechen") } },
            text = { TimeInput(state) },
        )
    }
    if (pickFolders) {
        var chosen by remember { mutableStateOf(review.buckets) }
        AlertDialog(
            onDismissRequest = { pickFolders = false },
            title = { Text("Ordner") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(vm.folders, key = { it.bucketId }) { f ->
                        val on = f.bucketId in chosen
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.98f) {
                                chosen = if (on) chosen - f.bucketId else chosen + f.bucketId
                            }.padding(vertical = 4.dp),
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
            },
            confirmButton = {
                TextButton(onClick = {
                    saveReview(review.copy(buckets = chosen))
                    pickFolders = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickFolders = false }) { Text("Abbrechen") } },
        )
    }
    if (uploadLimit) UploadLimitDialog {
        uploadLimit = false
        limitLabel = uploadLimitLabel()
    }
    if (connecting) TrunkConnectDialog(vm, onDone = { connecting = false })
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Row(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.4f
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.bounceClick(pressedScale = 0.99f, onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = (if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha))
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(title, subtitle, trailing = { Switch(checked = checked, onCheckedChange = null) }) { onChange(!checked) }
}
