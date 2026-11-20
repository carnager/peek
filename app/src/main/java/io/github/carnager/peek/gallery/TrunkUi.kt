package io.github.carnager.peek.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.carnager.peek.R
import io.github.carnager.peek.trunk.TrunkAccount
import io.github.carnager.peek.trunk.UploadWorker
import io.github.carnager.peek.ui.bounceClick
import io.github.carnager.peek.ui.brandFont

private val Night = Color(0xFF241A5C)
private val OnNight = Color(0xFFF3EEFF)
private val Sun = Color(0xFFFFC93C)

/** Upload state from WorkManager: (done, total) while running, else null. Syncs trunk when a batch ends. */
@Composable
fun rememberUploadProgress(vm: GalleryViewModel): Pair<Int, Int>? {
    val ctx = LocalContext.current
    val infos by remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(UploadWorker.WORK) }.collectAsState(emptyList())
    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running != null) {
        if (running == null && wasRunning) vm.syncTrunk()
        wasRunning = running != null
    }
    return running?.let {
        val total = it.progress.getInt(UploadWorker.KEY_TOTAL, 0)
        it.progress.getInt(UploadWorker.KEY_DONE, 0) to total
    }
}

@Composable
fun TrunkCard(vm: GalleryViewModel, upload: Pair<Int, Int>?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Night)
            .bounceClick(pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.mascot), null, Modifier.size(width = 54.dp, height = 38.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("trunk", fontFamily = brandFont(), fontSize = 26.sp, color = OnNight)
                Text(
                    when {
                        !vm.trunkConnected -> "Mit deinem trunk-Server verbinden"
                        vm.trunk.isEmpty() -> "Verbunden mit ${TrunkAccount.baseUrl?.substringAfter("://")}"
                        else -> "${vm.trunk.size} auf ${TrunkAccount.baseUrl?.substringAfter("://")}"
                    },
                    color = OnNight.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = OnNight.copy(alpha = 0.7f))
        }
        if (upload != null) {
            val (done, total) = upload
            Spacer(Modifier.height(12.dp))
            Text(
                if (total > 0) "Lädt hoch: ${done + 1} von $total" else "Lädt hoch …",
                color = OnNight, style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                    color = Sun, trackColor = OnNight.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                )
            } else {
                LinearProgressIndicator(color = Sun, trackColor = OnNight.copy(alpha = 0.2f), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun TrunkConnectDialog(vm: GalleryViewModel, onDone: () -> Unit) {
    var address by remember { mutableStateOf(TrunkAccount.baseUrl ?: "") }
    var user by remember { mutableStateOf(TrunkAccount.user ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val plain = address.trim().startsWith("http://")
    val ok = address.isNotBlank() && user.isNotBlank() && password.isNotEmpty() && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        shape = RoundedCornerShape(28.dp),
        icon = { Image(painterResource(R.drawable.mascot), null, Modifier.size(width = 72.dp, height = 50.dp)) },
        title = { Text("Mit trunk verbinden") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it; error = null },
                    label = { Text("Adresse") }, placeholder = { Text("fotos.example.org") },
                    singleLine = true, shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(if (plain) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (plain) {
                    Text(
                        "Unverschlüsselt: nur im eigenen Netz verwenden.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = user, onValueChange = { user = it; error = null },
                    label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = null },
                    label = { Text("Passwort") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = ok, onClick = {
                busy = true
                vm.trunkConnect(address, user.trim(), password) { err ->
                    busy = false
                    if (err == null) onDone() else error = err
                }
            }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Verbinden")
            }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Abbrechen") } },
    )
}

private val LIMITS = listOf(0L to "Original", 4L shl 20 to "Höchstens 4 MB", 2L shl 20 to "Höchstens 2 MB", 1L shl 20 to "Höchstens 1 MB", 500L shl 10 to "Höchstens 500 KB")

/** How big photos may be when they go up; bigger ones are sent as smaller JPEGs. */
@Composable
fun UploadLimitDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var current by remember { mutableLongStateOf(TrunkAccount.uploadLimit) }
    AlertDialog(
        onDismissRequest = onDone,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Upload-Größe") },
        text = {
            Column {
                Text(
                    "Größere Fotos werden vor dem Hochladen als JPEG verkleinert. Datum, Kamera und Ort bleiben erhalten. Videos gehen immer im Original.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LIMITS.forEach { (bytes, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).bounceClick(pressedScale = 0.98f) {
                            current = bytes
                            TrunkAccount.setUploadLimit(ctx, bytes)
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == bytes, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp), fontWeight = if (current == bytes) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Fertig") } },
    )
}
