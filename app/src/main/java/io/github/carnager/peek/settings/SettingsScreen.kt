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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.carnager.peek.BuildConfig
import io.github.carnager.peek.R
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
    val ctx = LocalContext.current
    val camera = remember(vm.folders) { vm.folders.firstOrNull { it.cover.relativePath?.startsWith("DCIM/Camera", ignoreCase = true) == true }?.bucketId }
    // Until the review is set up, it would start with the camera folder.
    val review = vm.reviewSettings.let { if (it.configured) it else it.copy(buckets = setOfNotNull(camera)) }
    fun saveReview(s: Review.Settings) = vm.saveReviewSettings(s)
    var pickTime by remember { mutableStateOf(false) }
    var pickFolders by remember { mutableStateOf(false) }
    var pickHidden by remember { mutableStateOf(false) }
    var pickFeed by remember { mutableStateOf(false) }
    var uploadLimit by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var limitLabel by remember { mutableStateOf(uploadLimitLabel(ctx)) }
    var showLinks by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_back)) } },
                    title = { Text(stringResource(R.string.settings_title)) },
                )
            },
        ) { pad ->
            LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp)) {
                item { Section(stringResource(R.string.settings_review_section)) }
                item {
                    SwitchRow(stringResource(R.string.settings_remind), stringResource(R.string.settings_remind_sub), review.remind) { on ->
                        if (!on) saveReview(review.copy(remind = false))
                        else ops.requestNotifications { ok ->
                            if (ok) saveReview(review.copy(remind = true)) else vm.message = Message(ctx.getString(R.string.settings_remind_no_notifications))
                        }
                    }
                }
                item {
                    Row(stringResource(R.string.settings_time), "%02d:%02d".format(review.hour, review.minute), enabled = review.remind) { pickTime = true }
                }
                item {
                    val names = vm.folders.filter { it.bucketId in review.buckets }.map { it.name }
                    Row(stringResource(R.string.settings_folders), when {
                        names.isEmpty() -> stringResource(R.string.settings_folders_none)
                        names.size <= 3 -> names.joinToString(", ")
                        else -> pluralStringResource(R.plurals.settings_folders_count, names.size, names.size)
                    }) { pickFolders = true }
                }
                item {
                    val n = vm.reviewPending.size
                    Row(stringResource(R.string.settings_review_now), if (!vm.reviewSettings.configured) stringResource(R.string.settings_review_now_sub) else if (n == 0) stringResource(R.string.settings_review_nothing_new) else pluralStringResource(R.plurals.settings_review_new_count, n, n)) {
                        if (!vm.reviewSettings.configured) saveReview(review)
                        onClose()
                        vm.startReview()
                    }
                }

                item { Section(stringResource(R.string.settings_folders_section)) }
                // Without trunk, the timeline is the phone's: all folders, or only the watched ones.
                if (!vm.trunkConnected) item {
                    Row(stringResource(R.string.settings_local_feed),
                        if (vm.feedWatchedOnly) stringResource(R.string.settings_local_feed_watched, vm.watchedFolderNames.joinToString(", ").ifEmpty { "–" })
                        else stringResource(R.string.settings_local_feed_all)) { pickFeed = true }
                }
                item {
                    val names = vm.folders.filter { it.bucketId in vm.hiddenFolders }.map { it.name }
                    Row(stringResource(R.string.settings_hidden_folders), when {
                        names.isEmpty() -> stringResource(R.string.settings_hidden_folders_none)
                        names.size <= 3 -> names.joinToString(", ")
                        else -> pluralStringResource(R.plurals.settings_folders_count, names.size, names.size)
                    }) { pickHidden = true }
                }

                item { Section("trunk") }
                if (vm.trunkConnected) {
                    item { Row(stringResource(R.string.settings_server), stringResource(R.string.settings_server_sub, TrunkAccount.user.toString(), TrunkAccount.baseUrl?.substringAfter("://").toString())) }
                    item { Row(stringResource(R.string.settings_upload_size), limitLabel) { uploadLimit = true } }
                    item { Row(stringResource(R.string.settings_links), stringResource(R.string.settings_links_sub)) { showLinks = true } }
                    item {
                        // Content search: the model on this phone, to free its space.
                        LaunchedEffect(Unit) { vm.refreshContent() }
                        val c = vm.content
                        val bytes = remember(c) { io.github.carnager.peek.trunk.ContentSearch.downloadedBytes(ctx) }
                        Row(
                            stringResource(R.string.settings_content),
                            when {
                                c is io.github.carnager.peek.gallery.GalleryViewModel.Content.Ready || bytes > 0 ->
                                    stringResource(R.string.settings_content_ready, bytes / (1 shl 20))
                                c is io.github.carnager.peek.gallery.GalleryViewModel.Content.Off -> stringResource(R.string.settings_content_none)
                                else -> stringResource(R.string.settings_content_available)
                            },
                        ) { if (bytes > 0) vm.removeContentModel() }
                    }
                    item {
                        var notifyShared by remember { mutableStateOf(io.github.carnager.peek.trunk.SharedNews.enabled(ctx)) }
                        SwitchRow(stringResource(R.string.settings_notify_shared), stringResource(R.string.settings_notify_shared_sub), notifyShared) {
                            notifyShared = it
                            io.github.carnager.peek.trunk.SharedNews.setEnabled(ctx, it)
                        }
                    }
                    item { Row(stringResource(R.string.settings_logout), stringResource(R.string.settings_logout_sub), tint = MaterialTheme.colorScheme.error) { vm.trunkLogout() } }
                } else {
                    item { Row(stringResource(R.string.settings_connect), stringResource(R.string.settings_connect_sub)) { connecting = true } }
                }

                item { Section(stringResource(R.string.settings_about)) }
                item { Row("peek", stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) }
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
                }) { Text(stringResource(R.string.settings_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.settings_cancel)) } },
            text = { TimeInput(state) },
        )
    }
    if (pickFolders) {
        FolderPicker(vm, stringResource(R.string.settings_folders), stringResource(R.string.settings_folders_explain), review.buckets, { pickFolders = false }) {
            saveReview(review.copy(buckets = it))
        }
    }
    if (pickFeed) {
        AlertDialog(
            onDismissRequest = { pickFeed = false },
            title = { Text(stringResource(R.string.settings_local_feed)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_local_feed_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                    listOf(
                        false to stringResource(R.string.settings_local_feed_all),
                        true to stringResource(R.string.settings_local_feed_watched, vm.watchedFolderNames.joinToString(", ").ifEmpty { "–" }),
                    ).forEach { (on, label) ->
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bounceClick(pressedScale = 0.98f) { vm.setFeedWatched(on); pickFeed = false }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = vm.feedWatchedOnly == on, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickFeed = false }) { Text(stringResource(R.string.settings_ok)) } },
        )
    }
    if (pickHidden) {
        FolderPicker(vm, stringResource(R.string.settings_hidden_folders), stringResource(R.string.settings_hidden_folders_explain), vm.hiddenFolders, { pickHidden = false }) {
            vm.hideFolders(it)
        }
    }
    if (uploadLimit) UploadLimitDialog {
        uploadLimit = false
        limitLabel = uploadLimitLabel(ctx)
    }
    if (connecting) TrunkConnectDialog(vm, onDone = { connecting = false })
    if (showLinks) io.github.carnager.peek.gallery.LinksSheet(vm) { showLinks = false }
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

/** Folders to tick, with a line saying what ticking means. */
@Composable
private fun FolderPicker(vm: GalleryViewModel, title: String, explain: String, initial: Set<Long>, onDismiss: () -> Unit, onSave: (Set<Long>) -> Unit) {
    var chosen by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(explain, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
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
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen); onDismiss() }) { Text(stringResource(R.string.settings_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
