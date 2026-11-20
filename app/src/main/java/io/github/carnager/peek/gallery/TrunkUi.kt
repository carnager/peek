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
import androidx.compose.ui.res.stringResource
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
    // Sync once for every finished batch, however short; a quick upload may never be seen running.
    val handled = remember { HashSet<java.util.UUID>() }
    LaunchedEffect(infos) {
        val finished = infos.filter { it.state.isFinished }.map { it.id }
        if (finished.any { it !in handled }) {
            handled += finished
            vm.syncTrunk()
        }
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
                        !vm.trunkConnected -> stringResource(R.string.gallery_trunk_card_connect)
                        vm.trunk.isEmpty() -> stringResource(R.string.gallery_trunk_card_connected, TrunkAccount.baseUrl?.substringAfter("://").toString())
                        else -> stringResource(R.string.gallery_trunk_card_count, vm.trunk.size, TrunkAccount.baseUrl?.substringAfter("://").toString())
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
                if (total > 0) stringResource(R.string.gallery_uploading_progress, done + 1, total) else stringResource(R.string.gallery_uploading),
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
        title = { Text(stringResource(R.string.gallery_connect_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it; error = null },
                    label = { Text(stringResource(R.string.gallery_connect_address)) }, placeholder = { Text("fotos.example.org") },
                    singleLine = true, shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(if (plain) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (plain) {
                    Text(
                        stringResource(R.string.gallery_connect_unencrypted),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = user, onValueChange = { user = it; error = null },
                    label = { Text(stringResource(R.string.gallery_connect_name)) }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = null },
                    label = { Text(stringResource(R.string.gallery_connect_password)) }, singleLine = true, shape = RoundedCornerShape(16.dp),
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
                else Text(stringResource(R.string.gallery_connect))
            }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text(stringResource(R.string.gallery_cancel)) } },
    )
}

/** Upload limits with their size in words; null is the original. */
private val LIMITS = listOf(0L to null, 4L shl 20 to "4 MB", 2L shl 20 to "2 MB", 1L shl 20 to "1 MB", 500L shl 10 to "500 KB")

private fun limitLabel(ctx: android.content.Context, size: String?): String =
    if (size == null) ctx.getString(R.string.gallery_limit_original) else ctx.getString(R.string.gallery_limit_max, size)

/** The current upload limit in words, for the settings. */
fun uploadLimitLabel(ctx: android.content.Context): String = limitLabel(ctx, LIMITS.firstOrNull { it.first == TrunkAccount.uploadLimit }?.second)

/** How big photos may be when they go up; bigger ones are sent as smaller JPEGs. */
@Composable
fun UploadLimitDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var current by remember { mutableLongStateOf(TrunkAccount.uploadLimit) }
    AlertDialog(
        onDismissRequest = onDone,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.gallery_limit_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.gallery_limit_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LIMITS.forEach { (bytes, size) ->
                    val label = limitLabel(ctx, size)
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
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.gallery_done)) } },
    )
}
