package io.github.carnager.peek.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.draw.clip
import io.github.carnager.peek.ui.ToolSheet
import io.github.carnager.peek.ui.bounceClick
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private fun Tool.icon(): ImageVector = when (this) {
    Tool.CROP -> Icons.Rounded.Crop
    Tool.ADJUST -> Icons.Rounded.Tune
    Tool.FILTER -> Icons.Rounded.AutoAwesome
    Tool.DRAW -> Icons.Rounded.Draw
    Tool.TEXT -> Icons.Rounded.TextFields
    Tool.RESIZE -> Icons.Rounded.PhotoSizeSelectLarge
}

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onClose: () -> Unit,
    onSaveCopy: (SaveFormat, Int) -> Unit,
    onOverwrite: (Int) -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showResize by remember { mutableStateOf(false) }

    fun close() = if (vm.isDirty) confirmDiscard = true else onClose()
    BackHandler {
        when {
            vm.tool != null -> vm.closeTool()
            else -> close()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val preview = vm.preview
        if (preview == null) {
            vm.error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
        } else {
            AnimatedContent(
                targetState = vm.tool?.takeIf { it != Tool.RESIZE },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "tool",
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
            ) { tool ->
                Column(Modifier.fillMaxSize()) {
                    if (tool == null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(onClick = ::close) { Icon(Icons.Rounded.Close, "Schließen") }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Rückgängig") }
                            IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Wiederholen") }
                            Spacer(Modifier.width(6.dp))
                            Button(onClick = { showSave = true }, enabled = vm.isDirty) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Speichern", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        // Holding a finger on the image shows the original for comparison.
                        var compare by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            ImageStage(
                                bitmap = if (compare) vm.originalPreview ?: preview else preview,
                                // The original may have a different shape, so it is shown unzoomed.
                                zoom = if (compare) null else vm.zoom,
                                gestures = Modifier.holdToCompare { compare = it },
                            )
                            androidx.compose.animation.AnimatedVisibility(
                                visible = compare,
                                enter = fadeIn(), exit = fadeOut(),
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                            ) {
                                Text(
                                    "Original",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 6.dp),
                                )
                            }
                        }
                        ToolSheet {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                Tool.entries.forEach { t ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(18.dp))
                                            .bounceClick(pressedScale = 0.88f) {
                                                if (t == Tool.RESIZE) showResize = true else vm.openTool(t)
                                            }
                                            .padding(horizontal = 4.dp, vertical = 6.dp),
                                    ) {
                                        Box(
                                            Modifier.size(50.dp).clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                            contentAlignment = Alignment.Center,
                                        ) { Icon(t.icon(), null, tint = MaterialTheme.colorScheme.primary) }
                                        Spacer(Modifier.height(6.dp))
                                        Text(t.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                        }
                    } else {
                        val m = Modifier.weight(1f)
                        when (tool) {
                            Tool.CROP -> CropTool(vm, m)
                            Tool.ADJUST -> AdjustTool(vm, m)
                            Tool.FILTER -> FilterTool(vm, m)
                            Tool.DRAW -> DrawTool(vm, m)
                            Tool.TEXT -> TextTool(vm, m)
                            Tool.RESIZE -> {}
                        }
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = vm.busy, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 4.dp, modifier = Modifier.size(44.dp))
            }
        }
    }

    vm.error?.takeIf { vm.preview != null }?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.error = null },
            confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Änderungen verwerfen?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Weiter bearbeiten") } },
        )
    }

    if (showResize) {
        vm.current?.let { cur ->
            ResizeDialog(
                width = cur.width,
                height = cur.height,
                onDismiss = { showResize = false },
                onApply = { w, h ->
                    showResize = false
                    vm.commit { ImageOps.resize(it, w, h) }
                },
            )
        }
    }

    if (showSave) {
        val src = vm.source
        val cur = vm.current
        if (src != null && cur != null) {
            SaveDialog(
                source = src,
                width = cur.width,
                height = cur.height,
                onDismiss = { showSave = false },
                onSaveCopy = { f, q -> showSave = false; onSaveCopy(f, q) },
                onOverwrite = { q -> showSave = false; onOverwrite(q) },
            )
        }
    }
}

/** Cancel / title / apply row at the bottom of every tool. */
@Composable
fun ToolActions(title: String, onCancel: () -> Unit, onApply: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "Abbrechen") }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        FilledIconButton(onClick = onApply, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Check, "Übernehmen") }
    }
}

@Composable
private fun ResizeDialog(width: Int, height: Int, onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    var w by remember { mutableStateOf(width.toString()) }
    var h by remember { mutableStateOf(height.toString()) }
    var locked by remember { mutableStateOf(true) }
    val ratio = width.toFloat() / height
    val maxPixels = ImageIO.maxPixels()

    fun setPercent(p: Int) {
        w = (width * p / 100).coerceAtLeast(1).toString()
        h = (height * p / 100).coerceAtLeast(1).toString()
    }
    val wi = w.toIntOrNull()
    val hi = h.toIntOrNull()
    val valid = wi != null && hi != null && wi in 1..30000 && hi in 1..30000 && wi.toLong() * hi <= maxPixels

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Größe ändern") },
        text = {
            Column {
                Text("Aktuell $width × $height", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = w,
                        onValueChange = { v ->
                            w = v.filter(Char::isDigit).take(5)
                            if (locked) w.toIntOrNull()?.let { h = (it / ratio).roundToInt().coerceAtLeast(1).toString() }
                        },
                        label = { Text("Breite") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    IconToggleButton(checked = locked, onCheckedChange = { locked = it }) {
                        Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, "Seitenverhältnis sperren")
                    }
                    OutlinedTextField(
                        value = h,
                        onValueChange = { v ->
                            h = v.filter(Char::isDigit).take(5)
                            if (locked) h.toIntOrNull()?.let { w = (it * ratio).roundToInt().coerceAtLeast(1).toString() }
                        },
                        label = { Text("Höhe") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(25, 50, 75).forEach { p -> androidx.compose.material3.SuggestionChip(onClick = { setPercent(p) }, label = { Text("$p %") }, shape = CircleShape) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Common long-edge targets for messengers and the web.
                    listOf(1080, 1920, 2560).forEach { side ->
                        androidx.compose.material3.SuggestionChip(
                            shape = CircleShape,
                            onClick = {
                                locked = true
                                if (width >= height) {
                                    w = side.toString(); h = (side / ratio).roundToInt().toString()
                                } else {
                                    h = side.toString(); w = (side * ratio).roundToInt().toString()
                                }
                            },
                            label = { Text("${side}px") },
                        )
                    }
                }
                if (!valid && wi != null && hi != null && wi.toLong() * hi > maxPixels) {
                    Text("Zu groß für den Speicher", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onApply(wi!!, hi!!) }) { Text("Übernehmen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SaveDialog(
    source: SourceInfo,
    width: Int,
    height: Int,
    onDismiss: () -> Unit,
    onSaveCopy: (SaveFormat, Int) -> Unit,
    onOverwrite: (Int) -> Unit,
) {
    val originalFormat = SaveFormat.forMime(source.mimeType)
    var format by remember { mutableStateOf(originalFormat ?: SaveFormat.JPEG) }
    var quality by remember { mutableFloatStateOf(92f) }
    val downscaled = source.originalWidth.toLong() * source.originalHeight > ImageIO.maxPixels()

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("Speichern", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "$width × $height px",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (downscaled) {
                Text(
                    "Das Original (${source.originalWidth} × ${source.originalHeight}) wurde zum Bearbeiten verkleinert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SaveFormat.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = f == format,
                        onClick = { format = f },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, SaveFormat.entries.size),
                        label = { Text(f.name) },
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(format.lossy) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Qualität", style = MaterialTheme.typography.labelLarge)
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 50f..100f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Text("${quality.roundToInt()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(32.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onSaveCopy(format, quality.roundToInt()) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Als Kopie speichern", style = MaterialTheme.typography.titleSmall) }
            Spacer(Modifier.height(10.dp))
            if (originalFormat != null && format == originalFormat) {
                OutlinedButton(
                    onClick = { onOverwrite(quality.roundToInt()) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Original ersetzen", style = MaterialTheme.typography.titleSmall) }
            } else {
                Text(
                    "Original ersetzen geht nur im Originalformat${originalFormat?.let { " (${it.name})" } ?: ""}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
