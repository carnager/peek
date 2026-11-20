package io.github.carnager.peek.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import io.github.carnager.peek.ui.PillChip
import io.github.carnager.peek.ui.ToolSheet
import io.github.carnager.peek.ui.bounceClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun AdjustTool(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val bmp = vm.preview ?: return
    var adj by remember { mutableStateOf(Adjustments()) }
    var param by remember { mutableStateOf(AdjustParam.BRIGHTNESS) }
    var compare by remember { mutableStateOf(false) }

    Column(modifier) {
        ImageStage(
            bitmap = bmp,
            modifier = Modifier.weight(1f),
            colorMatrix = if (compare) null else adj.matrix(),
            vignette = if (compare) 0f else adj.vignette,
            gestures = Modifier.holdToCompare { compare = it },
        )
        ToolSheet {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdjustParam.entries.forEach { p ->
                val v = p.get(adj)
                PillChip(selected = p == param, label = if (v != 0f) "${p.label} ${(v * 100).roundToInt()}" else p.label) { param = p }
            }
        }
        ValueSlider(
            value = param.get(adj),
            min = param.min,
            onChange = { adj = param.set(adj, it) },
            onReset = { adj = param.set(adj, 0f) },
        )
        ToolActions(
            title = "Anpassen",
            onCancel = vm::closeTool,
            onApply = {
                val a = adj
                if (a.isIdentity) vm.closeTool() else vm.commit { ImageOps.applyColor(it, a.matrix(), a.vignette) }
            },
        )
        }
    }
}

@Composable
fun FilterTool(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val bmp = vm.preview ?: return
    var selected by remember { mutableIntStateOf(0) }
    var intensity by remember { mutableFloatStateOf(1f) }
    var compare by remember { mutableStateOf(false) }
    val thumb = remember(bmp) { thumbnail(bmp, 220) }
    val preset = FILTERS[selected]

    Column(modifier) {
        ImageStage(
            bitmap = bmp,
            modifier = Modifier.weight(1f),
            colorMatrix = if (compare) null else preset.matrix(intensity),
            vignette = if (compare) 0f else preset.vignette * intensity,
            gestures = Modifier.holdToCompare { compare = it },
        )
        ToolSheet {
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) {
            itemsIndexed(FILTERS) { i, f ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(76.dp).bounceClick(pressedScale = 0.9f, ripple = false) { if (selected == i) intensity = 1f; selected = i },
                ) {
                    val shape = RoundedCornerShape(18.dp)
                    Box(
                        Modifier.size(72.dp).clip(shape)
                            .then(if (i == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    ) {
                        Image(
                            bitmap = thumb,
                            contentDescription = f.name,
                            contentScale = ContentScale.Crop,
                            colorFilter = ColorFilter.colorMatrix(ColorMatrix(f.matrix(1f).array)),
                            modifier = Modifier.size(72.dp),
                        )
                    }
                    Text(
                        f.name,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        color = if (i == selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        if (selected != 0) {
            ValueSlider(value = intensity, min = 0f, max = 1f, onChange = { intensity = it }, onReset = { intensity = 1f })
        } else {
            Box(Modifier.height(48.dp))
        }
        ToolActions(
            title = "Filter",
            onCancel = vm::closeTool,
            onApply = {
                val p = preset
                val k = intensity
                if (selected == 0 || k == 0f) vm.closeTool() else vm.commit { ImageOps.applyColor(it, p.matrix(k), p.vignette * k) }
            },
        )
        }
    }
}

private fun thumbnail(src: Bitmap, side: Int): androidx.compose.ui.graphics.ImageBitmap {
    val s = minOf(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
    return Bitmap.createScaledBitmap(square, side, side, true).asImageBitmap()
}

/** A slider with the value shown on the left; tapping the value resets it. */
@Composable
fun ValueSlider(value: Float, min: Float, max: Float = 1f, onChange: (Float) -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (value * 100).roundToInt().toString(),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp).clickable(onClick = onReset),
        )
        Slider(value = value, onValueChange = onChange, valueRange = min..max, modifier = Modifier.weight(1f))
    }
}

/** Shows the unedited image while a finger rests on it. */
fun Modifier.holdToCompare(onChange: (Boolean) -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            onChange(true)
            waitForUpOrCancellation()
            onChange(false)
        }
    }
)
