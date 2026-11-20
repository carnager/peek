package io.github.carnager.peek.viewer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import io.github.carnager.peek.ui.bounceClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/** What the photo's EXIF data says, as far as it says anything. */
data class PhotoMeta(
    val camera: String? = null,
    val lens: String? = null,
    val aperture: Double? = null,
    val exposure: Double? = null,
    val iso: Int? = null,
    val focal: Double? = null,
    val focal35: Int? = null,
    val flash: Boolean? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitude: Double? = null,
    val software: String? = null,
    /** Already known place name (from trunk), so no geocoder is asked. */
    val place: String? = null,
    val caption: String? = null,
)

private fun hasMediaLocation(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun readMeta(ctx: Context, uri: Uri): PhotoMeta = runCatching {
    // Android removes GPS from the stream unless we ask for the original, which needs ACCESS_MEDIA_LOCATION.
    val source = if (uri.authority == MediaStore.AUTHORITY && hasMediaLocation(ctx)) {
        runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
    } else uri
    val stream = runCatching { ctx.contentResolver.openInputStream(source) }.getOrNull() ?: ctx.contentResolver.openInputStream(uri)
    stream?.use { input ->
        val e = ExifInterface(input)
        fun d(tag: String) = e.getAttributeDouble(tag, 0.0).takeIf { it > 0 }
        val make = e.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = e.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        // "Google Pixel 10 Pro" rather than "Google Google Pixel 10 Pro".
        val camera = when {
            model.isEmpty() -> make
            make.isEmpty() || model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        }.ifBlank { null }
        val ll = e.latLong
        PhotoMeta(
            camera = camera,
            lens = e.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.ifBlank { null },
            aperture = d(ExifInterface.TAG_F_NUMBER),
            exposure = d(ExifInterface.TAG_EXPOSURE_TIME),
            iso = e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
            focal = d(ExifInterface.TAG_FOCAL_LENGTH),
            focal35 = e.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 },
            flash = e.getAttribute(ExifInterface.TAG_FLASH)?.toIntOrNull()?.let { it and 1 == 1 },
            lat = ll?.get(0)?.takeIf { ll[0] != 0.0 || ll[1] != 0.0 },
            lon = ll?.get(1)?.takeIf { ll[0] != 0.0 || ll[1] != 0.0 },
            altitude = e.getAltitude(Double.NaN).takeIf { !it.isNaN() },
            software = e.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()?.ifBlank { null },
        )
    }
}.getOrNull() ?: PhotoMeta()

/** A readable place name for the coordinates, if the phone has a geocoder. */
private suspend fun placeName(ctx: Context, lat: Double, lon: Double): String? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(ctx, Locale.getDefault())
    val address = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) { cont.resume(addresses.firstOrNull()) }
                    override fun onError(errorMessage: String?) { cont.resume(null) }
                })
            }
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION") geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
        }
    }.getOrNull() ?: return null
    return listOfNotNull(
        address.thoroughfare?.let { t -> address.subThoroughfare?.let { "$t $it" } ?: t },
        address.locality ?: address.subAdminArea,
        address.countryName,
    ).distinct().joinToString(", ").ifBlank { null }
}

@Composable
fun InfoSheet(item: ViewerItem, onCaption: ((String) -> Unit)? = null) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaLocation(ctx)) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences("gallery", 0)
        if (item.meta == null && !granted && !item.isVideo && !prefs.getBoolean("askedMediaLocation", false)) {
            prefs.edit().putBoolean("askedMediaLocation", true).apply()
            askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
    // Keyed on meta too: a caption saved from this sheet arrives as new meta for the same photo.
    val meta by produceState(item.meta ?: PhotoMeta(), item.uri, granted, item.meta) {
        value = item.meta ?: if (item.isVideo) PhotoMeta() else withContext(Dispatchers.IO) { readMeta(ctx, item.uri) }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        // Header: when, and what file.
        if (item.date > 0) {
            Text(
                DateFormat.getDateInstance(DateFormat.FULL).format(Date(item.date)),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.date)) + " Uhr",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            item.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(top = 6.dp),
        )

        // Caption: shown when there is one; editable where the photo lives on trunk.
        var editCaption by remember { mutableStateOf(false) }
        val caption = meta.caption
        if (caption != null || onCaption != null) {
            Text(
                caption ?: "Beschreibung hinzufügen",
                style = if (caption != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = if (caption != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp).then(
                    if (onCaption != null) Modifier.clip(RoundedCornerShape(8.dp)).bounceClick(pressedScale = 0.98f) { editCaption = true } else Modifier,
                ),
            )
        }
        if (editCaption && onCaption != null) {
            var text by remember { mutableStateOf(caption ?: "") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { editCaption = false },
                title = { Text("Beschreibung") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { onCaption(text.trim()); editCaption = false }) { Text("Speichern") } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { editCaption = false }) { Text("Abbrechen") } },
            )
        }

        // Quick facts.
        Row(Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (item.width > 0) {
                Fact(Icons.Rounded.AspectRatio, "%.1f MP".format(item.width * item.height / 1e6), "${item.width} × ${item.height}")
            }
            if (item.isVideo && item.duration > 0) Fact(Icons.Rounded.PlayCircle, formatDuration(item.duration), "Länge")
            if (item.size > 0) Fact(Icons.Rounded.Storage, Formatter.formatShortFileSize(ctx, item.size), item.mimeType.substringAfter('/').uppercase())
            item.path?.let { Fact(Icons.Rounded.Folder, it.substringAfterLast('/').ifBlank { it }, "Ordner") }
        }

        // Camera.
        if (meta.camera != null || meta.aperture != null) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.PhotoCamera)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(meta.camera ?: "Kamera", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val lens = meta.lens ?: meta.focal35?.let { "$it mm Kleinbild-Äquivalent" }
                        lens?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                val chips = buildList {
                    meta.aperture?.let { add("ƒ/" + "%.1f".format(it).removeSuffix(",0").removeSuffix(".0")) }
                    meta.exposure?.let { add(if (it < 1) "1/${(1 / it).roundToInt()} s" else "%.1f s".format(it)) }
                    meta.iso?.let { add("ISO $it") }
                    meta.focal?.let { add("%.1f mm".format(it).replace(",0 ", " ").replace(".0 ", " ")) }
                    if (meta.flash == true) add("Blitz")
                }
                if (chips.isNotEmpty()) {
                    Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEach { c ->
                            Text(
                                c,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // Where.
        val lat = meta.lat
        val lon = meta.lon
        if (lat != null && lon != null) {
            val place by produceState(meta.place, lat, lon, meta.place) { value = meta.place ?: placeName(ctx, lat, lon) }
            Card(padding = 0.dp) {
                MapTiles(lat, lon, Modifier.fillMaxWidth().height(180.dp)) {
                    val geo = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
                }
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Place)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(place ?: "Aufnahmeort", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "%.5f, %.5f".format(Locale.ROOT, lat, lon) + (meta.altitude?.let { " · %.0f m".format(it) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else if (item.meta == null && !granted && !item.isVideo) {
            Card {
                Row(
                    Modifier.fillMaxWidth().bounceClick(pressedScale = 0.97f) { askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(Icons.Rounded.LocationOff)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Aufnahmeort ausgeblendet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Tippen, um peek Standortdaten in Fotos lesen zu lassen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        meta.software?.let {
            Text("Bearbeitet mit $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, start = 4.dp))
        }
    }
}

@Composable
private fun Card(padding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Fact(icon: ImageVector, value: String, label: String) {
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .widthIn(min = 92.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * A small static map: OpenStreetMap tiles around the point, laid out by hand. No map library,
 * no Google services. Tiles come through Coil and its disk cache.
 */
@Composable
private fun MapTiles(lat: Double, lon: Double, modifier: Modifier, onClick: () -> Unit) {
    val zoom = 15
    val n = 1 shl zoom
    // Web Mercator: position in tile units.
    val xf = (lon + 180.0) / 360.0 * n
    val latRad = Math.toRadians(lat)
    val yf = (1.0 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2.0 * n
    val tileDp = 128.dp
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds().bounceClick(pressedScale = 0.98f, ripple = false, onClick = onClick)) {
        val tilePx = with(density) { tileDp.toPx() }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val x0 = floor(xf - w / 2 / tilePx).toInt()
        val x1 = floor(xf + w / 2 / tilePx).toInt()
        val y0 = floor(yf - h / 2 / tilePx).toInt()
        val y1 = floor(yf + h / 2 / tilePx).toInt()
        for (ty in y0..y1) for (tx in x0..x1) {
            if (ty !in 0 until n) continue
            val wx = ((tx % n) + n) % n
            AsyncImage(
                model = "https://tile.openstreetmap.org/$zoom/$wx/$ty.png",
                contentDescription = null,
                modifier = Modifier
                    .offset { IntOffset(((tx - xf) * tilePx + w / 2).roundToInt(), ((ty - yf) * tilePx + h / 2).roundToInt()) }
                    .size(tileDp)
                    .background(Color(0xFFE8E4DC)),
            )
        }
        // Pin, its tip on the spot.
        Icon(
            Icons.Rounded.LocationOn, null,
            tint = Color(0xFFE53935),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-18).dp)
                .size(40.dp)
                .shadow(0.dp),
        )
        Text(
            "© OpenStreetMap",
            fontSize = 10.sp,
            color = Color(0xFF333333),
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
