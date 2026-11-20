package io.github.carnager.peek.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.carnager.peek.R
import io.github.carnager.peek.trunk.TrunkClient
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Corrects when and where photos on trunk were taken: a date and time for the earliest (the others
 * keep their distance), or a shift for a camera clock that was off; a town found by name, or no
 * place. Only trunk's records change, not the files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustSheet(vm: GalleryViewModel, images: List<MediaImage>, onDone: () -> Unit) {
    val remote = images.mapNotNull { it.remote }
    if (remote.isEmpty()) {
        onDone()
        return
    }
    val earliest = remote.minBy { it.takenAt }
    val current = remember(earliest) {
        earliest.localTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(earliest.takenAt), ZoneId.systemDefault())
    }
    var newTime by remember { mutableStateOf<LocalDateTime?>(null) }
    var shift by remember { mutableLongStateOf(0L) } // minutes
    var place by remember { mutableStateOf<TrunkClient.PlaceHit?>(null) }
    var clearPlace by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TrunkClient.PlaceHit>>(emptyList()) }
    var picking by remember { mutableStateOf(0) } // 1 date, 2 time
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300) // while typing
        results = runCatching { TrunkClient.places(query) }.getOrDefault(emptyList())
    }
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    val target = newTime ?: current.plusMinutes(shift).takeIf { shift != 0L }
    val places = remember(remote) { remote.map { it.placeLine }.distinct() }

    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(
                pluralStringResource(R.plurals.gallery_adjust_title, remote.size, remote.size),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.gallery_adjust_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            // When
            Text(stringResource(R.string.gallery_adjust_when), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            Text(
                if (remote.size > 1) stringResource(R.string.gallery_adjust_earliest, current.format(fmt)) else current.format(fmt),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (target != null) {
                Text("→ ${target.format(fmt)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            if (remote.size > 1) {
                Text(stringResource(R.string.gallery_adjust_keep_distance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { picking = 1 }) {
                    Icon(Icons.Rounded.EditCalendar, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.gallery_adjust_set_date))
                }
            }
            // A camera clock that was off: shift everything.
            Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, minutes) in listOf(
                    stringResource(R.string.gallery_adjust_minus_day) to -1440L,
                    stringResource(R.string.gallery_adjust_minus_hour) to -60L,
                    stringResource(R.string.gallery_adjust_plus_hour) to 60L,
                    stringResource(R.string.gallery_adjust_plus_day) to 1440L,
                )) {
                    AssistChip(onClick = { newTime = null; shift += minutes }, label = { Text(label) })
                }
            }

            // Where
            Text(stringResource(R.string.gallery_adjust_where), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            Text(
                when {
                    clearPlace -> stringResource(R.string.gallery_adjust_no_place)
                    place != null -> "→ ${place!!.label}"
                    places.size == 1 -> places[0] ?: stringResource(R.string.gallery_adjust_no_place)
                    else -> stringResource(R.string.gallery_adjust_various)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (clearPlace || place != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (clearPlace || place != null) FontWeight.SemiBold else FontWeight.Normal,
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.gallery_adjust_search_town)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            results.forEach { hit ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { place = hit; clearPlace = false }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (place?.id == hit.id) Icons.Rounded.Check else Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(hit.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            TextButton(onClick = { clearPlace = true; place = null }, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Rounded.LocationOff, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.gallery_adjust_remove_place))
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.gallery_cancel)) }
                Button(
                    enabled = target != null || place != null || clearPlace,
                    onClick = {
                        vm.trunkAdjust(
                            images,
                            localTime = newTime?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
                            shiftMinutes = shift.takeIf { newTime == null && it != 0L },
                            placeId = place?.id,
                            clearPlace = clearPlace,
                        )
                        onDone()
                    },
                ) { Text(stringResource(R.string.gallery_adjust_save)) }
            }
        }
    }

    if (picking == 1) {
        val start = (newTime ?: current).toLocalDate()
        val state = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val ms = state.selectedDateMillis
                    if (ms != null) {
                        val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        val time = (newTime ?: current).toLocalTime()
                        newTime = day.atTime(time)
                        shift = 0
                    }
                    picking = 2 // then the time
                }) { Text(stringResource(R.string.gallery_adjust_next)) }
            },
            dismissButton = { TextButton(onClick = { picking = 0 }) { Text(stringResource(R.string.gallery_cancel)) } },
        ) { DatePicker(state = state) }
    }
    if (picking == 2) {
        val base = newTime ?: current
        val state = rememberTimePickerState(base.hour, base.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current))
        AlertDialog(
            onDismissRequest = { picking = 0 },
            confirmButton = {
                TextButton(onClick = {
                    newTime = base.withHour(state.hour).withMinute(state.minute).withSecond(0)
                    shift = 0
                    picking = 0
                }) { Text(stringResource(R.string.gallery_adjust_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = 0 }) { Text(stringResource(R.string.gallery_cancel)) } },
            text = { TimePicker(state = state) },
        )
    }
}

