package io.github.carnager.peek.editor

import android.graphics.RectF
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import io.github.carnager.peek.ui.PillChip
import io.github.carnager.peek.ui.ToolSheet
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

val PALETTE: List<Int> = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF9E9E9E, 0xFFE53935, 0xFFFF7043, 0xFFFFB300, 0xFFFFEB3B,
    0xFF7CB342, 0xFF00897B, 0xFF29B6F6, 0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60, 0xFFF8BBD0, 0xFF795548,
).map { it.toInt() }

@Composable
fun ColorRow(selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        items(PALETTE) { c ->
            val isSel = c == selected
            Box(
                Modifier.size(if (isSel) 34.dp else 30.dp).clip(CircleShape).background(Color(c))
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f), CircleShape)
                    .clickable { onSelect(c) }
            )
        }
    }
}

@Composable
fun DrawTool(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val bmp = vm.preview ?: return
    var color by remember { mutableIntStateOf(0xFFE53935.toInt()) }
    var width by remember { mutableFloatStateOf(0.012f) }
    // 0 = pen, 1 = marker, 2 = arrow
    var mode by remember { mutableIntStateOf(0) }
    val live = remember { mutableStateListOf<Pair<Float, Float>>() }
    var imgRect by remember { mutableStateOf(RectF()) }
    val rect by rememberUpdatedState(imgRect)

    Column(modifier) {
        ImageStage(
            bitmap = bmp,
            modifier = Modifier.weight(1f),
            zoom = vm.zoom,
            onRect = { imgRect = it },
            gestures = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = rect
                    fun norm(o: Offset) = ((o.x - r.left) / r.width()).coerceIn(0f, 1f) to ((o.y - r.top) / r.height()).coerceIn(0f, 1f)
                    live.clear()
                    live += norm(down.position)
                    down.consume()
                    var multi = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size > 1) multi = true
                        val ch = ev.changes.first()
                        if (!ch.pressed) break
                        if (ch.positionChanged()) {
                            if (mode == 2) {
                                // An arrow only needs its start and the current tip.
                                if (live.size > 1) live.removeAt(live.lastIndex)
                            }
                            live += norm(ch.position)
                            ch.consume()
                        }
                    }
                    if (!multi && (mode != 2 || live.size > 1)) vm.strokes += Stroke(live.toList(), color, width, mode == 1, mode == 2)
                    live.clear()
                }
            },
        ) { r ->
            drawIntoCanvas { c ->
                vm.strokes.forEach { OverlayRenderer.drawStroke(c.nativeCanvas, it, r.left, r.top, r.width(), r.height()) }
                if (live.isNotEmpty()) OverlayRenderer.drawStroke(c.nativeCanvas, Stroke(live.toList(), color, width, mode == 1, mode == 2), r.left, r.top, r.width(), r.height())
            }
        }
        ToolSheet {
        ColorRow(color) { color = it }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconToggleButton(checked = mode == 0, onCheckedChange = { mode = 0 }) { Icon(Icons.Rounded.Brush, "Stift") }
            FilledTonalIconToggleButton(checked = mode == 1, onCheckedChange = { mode = 1 }) { Icon(Icons.Rounded.Highlight, "Marker") }
            FilledTonalIconToggleButton(checked = mode == 2, onCheckedChange = { mode = 2 }) { Icon(Icons.Rounded.ArrowOutward, "Pfeil") }
            // Brush size preview dot.
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                val d = with(LocalDensity.current) { (width * min(imgRect.width(), imgRect.height())).toDp() }.coerceIn(2.dp, 32.dp)
                Box(Modifier.size(d).clip(CircleShape).background(Color(color)))
            }
            Slider(value = width, onValueChange = { width = it }, valueRange = 0.003f..0.06f, modifier = Modifier.weight(1f))
            IconButton(onClick = { if (vm.strokes.isNotEmpty()) vm.strokes.removeAt(vm.strokes.lastIndex) }, enabled = vm.strokes.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Rounded.Undo, "Letzten Strich entfernen")
            }
        }
        ToolActions(title = "Zeichnen", onCancel = vm::closeTool, onApply = vm::applyOverlays)
        }
    }
}

@Composable
fun TextTool(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val bmp = vm.preview ?: return
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    var imgRect by remember { mutableStateOf(RectF()) }
    val rect by rememberUpdatedState(imgRect)
    var editing by remember { mutableStateOf<TextLayer?>(null) }
    var creating by remember { mutableStateOf(false) }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var fonts by remember { mutableStateOf(Fonts.all(ctx)) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val selected = vm.textLayers.firstOrNull { it.id == vm.selectedText }
    fun update(l: TextLayer) {
        val i = vm.textLayers.indexOfFirst { it.id == l.id }
        if (i >= 0) vm.textLayers[i] = l
    }

    LaunchedEffect(Unit) { if (vm.textLayers.isEmpty()) creating = true }

    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) { Fonts.import(ctx, uri) }
            if (f == null) {
                Toast.makeText(ctx, "Keine gültige Schriftdatei", Toast.LENGTH_SHORT).show()
            } else {
                fonts = Fonts.all(ctx)
                vm.lastFontId = f.id
                selected?.let { update(it.copy(fontId = f.id)) }
            }
        }
    }

    Column(modifier) {
        ImageStage(
            bitmap = bmp,
            modifier = Modifier.weight(1f),
            zoom = vm.zoom,
            // Two fingers belong to the selected text layer; without one they zoom the view.
            zoomEnabled = { vm.selectedText == null },
            onRect = { imgRect = it },
            gestures = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = rect
                    val px = down.position.x - r.left
                    val py = down.position.y - r.top
                    val hit = vm.textLayers.lastOrNull { OverlayRenderer.hit(ctx, it, px, py, r.width(), r.height(), 12 * density) }
                    val wasSelected = hit != null && hit.id == vm.selectedText
                    if (hit != null) {
                        vm.selectedText = hit.id
                        // Bring the touched layer to the front.
                        vm.textLayers.remove(hit)
                        vm.textLayers += hit
                    }
                    var moved = false
                    var travel = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.none { it.pressed }) break
                        val pan = ev.calculatePan()
                        val zoom = ev.calculateZoom()
                        val rot = ev.calculateRotation()
                        travel += pan.getDistance()
                        if (!moved && (travel > touchSlop || zoom != 1f && ev.calculateCentroidSize() > 0f)) moved = true
                        val target = vm.textLayers.firstOrNull { it.id == vm.selectedText }
                        // Two fingers anywhere scale and rotate the selected layer; one finger only drags a layer it grabbed.
                        val twoFinger = ev.changes.count { it.pressed } > 1
                        if (moved && target != null && (hit != null || twoFinger)) {
                            update(
                                target.copy(
                                    cx = (target.cx + pan.x / r.width()).coerceIn(0f, 1f),
                                    cy = (target.cy + pan.y / r.height()).coerceIn(0f, 1f),
                                    size = (target.size * zoom).coerceIn(0.015f, 0.6f),
                                    rotation = target.rotation + rot,
                                )
                            )
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                    if (!moved) {
                        when {
                            hit == null -> vm.selectedText = null
                            wasSelected -> editing = vm.textLayers.firstOrNull { it.id == hit.id }
                        }
                    }
                }
            },
        ) { r ->
            drawIntoCanvas { c ->
                vm.textLayers.forEach {
                    OverlayRenderer.drawText(ctx, c.nativeCanvas, it, r.left, r.top, r.width(), r.height(), selected = it.id == vm.selectedText, density = density)
                }
            }
        }

        ToolSheet {
        // Action row: add, edit, alignment, bold, delete.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = { creating = true }) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("Text")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { editing = selected }, enabled = selected != null) { Icon(Icons.Rounded.Edit, "Text ändern") }
            IconButton(
                onClick = { selected?.let { update(it.copy(align = TextAlign.entries[(it.align.ordinal + 1) % 3])) } },
                enabled = selected != null,
            ) {
                Icon(
                    when (selected?.align) {
                        TextAlign.LEFT -> Icons.AutoMirrored.Rounded.FormatAlignLeft
                        TextAlign.RIGHT -> Icons.AutoMirrored.Rounded.FormatAlignRight
                        else -> Icons.Rounded.FormatAlignCenter
                    },
                    "Ausrichtung",
                )
            }
            FilledTonalIconToggleButton(
                checked = selected?.bold == true,
                onCheckedChange = { b -> selected?.let { update(it.copy(bold = b)) } },
                enabled = selected != null,
            ) { Icon(Icons.Rounded.FormatBold, "Fett") }
            IconButton(
                onClick = { selected?.let { vm.textLayers.remove(it); vm.selectedText = null } },
                enabled = selected != null,
            ) { Icon(Icons.Rounded.Delete, "Text entfernen") }
        }

        // Style
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = selected?.style ?: vm.lastTextStyle
            TextStyleMode.entries.forEach { m ->
                PillChip(selected = m == current, label = m.label) {
                    vm.lastTextStyle = m
                    selected?.let { update(it.copy(style = m)) }
                }
            }
        }

        // Fonts, each previewed in itself.
        val currentFont = selected?.fontId ?: vm.lastFontId
        val fontList = androidx.compose.foundation.lazy.rememberLazyListState()
        // Keep the active font in view, e.g. the remembered one when the tool opens.
        LaunchedEffect(currentFont, fonts) {
            val i = fonts.indexOfFirst { it.id == currentFont }
            if (i >= 0 && fontList.layoutInfo.visibleItemsInfo.none { it.index == i && it.offset >= 0 }) fontList.animateScrollToItem(maxOf(0, i - 1))
        }
        LazyRow(
            Modifier.fillMaxWidth(),
            state = fontList,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(fonts, key = { it.id }) { f ->
                val family = remember(f.id) { FontFamily(Fonts.typeface(ctx, f.id)) }
                val sel = f.id == currentFont
                Box(
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            vm.lastFontId = f.id
                            selected?.let { update(it.copy(fontId = f.id)) }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        f.label,
                        fontFamily = family,
                        fontSize = 18.sp,
                        maxLines = 1,
                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Box(
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { importFont.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/octet-stream")) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Text(" Schrift importieren", maxLines = 1)
                    }
                }
            }
        }

        ColorRow(selected?.color ?: vm.lastTextColor) { c ->
            vm.lastTextColor = c
            selected?.let { update(it.copy(color = c)) }
        }

        ToolActions(title = "Text", onCancel = vm::closeTool, onApply = { vm.selectedText = null; vm.applyOverlays() })
        }
    }

    if (creating || editing != null) {
        TextInputDialog(
            initial = editing?.text ?: "",
            fontId = editing?.fontId ?: vm.lastFontId,
            color = editing?.color ?: vm.lastTextColor,
            onDismiss = { creating = false; editing = null },
            onConfirm = { text ->
                val e = editing
                if (e != null) {
                    if (text.isBlank()) {
                        vm.textLayers.removeAll { it.id == e.id }
                        vm.selectedText = null
                    } else {
                        update(e.copy(text = text))
                    }
                } else if (text.isNotBlank()) {
                    val layer = TextLayer(
                        id = System.nanoTime(),
                        text = text,
                        fontId = vm.lastFontId,
                        color = vm.lastTextColor,
                        style = vm.lastTextStyle,
                        // Stagger new layers so they do not stack exactly on top of each other.
                        cy = (0.5f + 0.08f * (vm.textLayers.size % 4)).coerceAtMost(0.85f),
                    )
                    vm.textLayers += layer
                    vm.selectedText = layer.id
                }
                creating = false
                editing = null
            },
        )
    }
}

/** Full-screen text entry that previews the text in its font and colour, like story editors do. */
@Composable
private fun TextInputDialog(initial: String, fontId: String, color: Int, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val family = remember(fontId) { FontFamily(Fonts.typeface(ctx, fontId)) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)).systemBarsPadding().imePadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Abbrechen", color = Color.White) }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Button(onClick = { onConfirm(text.text) }) { Text("Fertig") }
            }
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color(color),
                    fontFamily = family,
                    fontSize = 36.sp,
                    lineHeight = 42.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 10f),
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 28.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (text.text.isEmpty()) {
                            Text("Text eingeben", color = Color.White.copy(alpha = 0.4f), fontFamily = family, fontSize = 36.sp)
                        }
                        inner()
                    }
                },
            )
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
