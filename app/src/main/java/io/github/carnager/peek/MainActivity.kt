package io.github.carnager.peek

import android.Manifest
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.carnager.peek.gallery.MediaImage
import io.github.carnager.peek.gallery.MediaOps
import io.github.carnager.peek.gallery.Message
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil3.SingletonImageLoader
import io.github.carnager.peek.editor.EditorActivity
import io.github.carnager.peek.gallery.GalleryScreen
import io.github.carnager.peek.gallery.GalleryViewModel
import io.github.carnager.peek.ui.PeekTheme

class MainActivity : ComponentActivity() {
    private val vm: GalleryViewModel by viewModels()

    private var pendingOk: (() -> Unit)? = null
    private val mediaRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val ok = pendingOk
        pendingOk = null
        if (res.resultCode == RESULT_OK) ok?.invoke()
    }

    /** Runs a MediaStore consent request; with MANAGE_MEDIA it completes without showing anything. */
    private fun request(pi: PendingIntent, onOk: () -> Unit) {
        pendingOk = onOk
        mediaRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    private var pendingUpload: List<MediaImage> = emptyList()
    private val uploadPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startUpload() }

    private fun startUpload() {
        val images = pendingUpload
        pendingUpload = emptyList()
        if (images.isEmpty()) return
        io.github.carnager.peek.trunk.UploadWorker.enqueue(this, images)
        vm.message = Message(if (images.size == 1) "1 Datei wird zu trunk hochgeladen" else "${images.size} Dateien werden zu trunk hochgeladen")
    }

    private fun plural(n: Int) = if (n == 1) "1 Bild" else "$n Bilder"

    private val ops = object : MediaOps {
        override fun trash(images: List<MediaImage>) {
            if (images.isEmpty()) return
            request(MediaStore.createTrashRequest(contentResolver, images.map { it.uri }, true)) {
                vm.selection = emptySet()
                vm.reload()
                vm.message = Message("${plural(images.size)} im Papierkorb", "Rückgängig") { untrash(images, quiet = true) }
            }
        }

        override fun restore(images: List<MediaImage>) = untrash(images, quiet = false)

        private fun untrash(images: List<MediaImage>, quiet: Boolean) {
            if (images.isEmpty()) return
            request(MediaStore.createTrashRequest(contentResolver, images.map { it.uri }, false)) {
                vm.selection = emptySet()
                vm.reload()
                if (!quiet) vm.message = Message("${plural(images.size)} wiederhergestellt")
            }
        }

        override fun deleteForever(images: List<MediaImage>) {
            if (images.isEmpty()) return
            request(MediaStore.createDeleteRequest(contentResolver, images.map { it.uri })) {
                vm.selection = emptySet()
                vm.reload()
                vm.message = Message("${plural(images.size)} endgültig gelöscht")
            }
        }

        override fun setFavorite(images: List<MediaImage>, favorite: Boolean) {
            if (images.isEmpty()) return
            request(MediaStore.createFavoriteRequest(contentResolver, images.map { it.uri }, favorite)) {
                vm.reload()
            }
        }

        override fun edit(uri: Uri) = editor.launch(EditorActivity.intent(this@MainActivity, uri))

        override fun editTrunk(uri: Uri, asset: io.github.carnager.peek.trunk.RemoteAsset) =
            editor.launch(EditorActivity.trunkIntent(this@MainActivity, uri, asset))

        override fun uploadToTrunk(images: List<MediaImage>) {
            if (images.isEmpty()) return
            pendingUpload = images
            // Notifications show the progress; media location keeps GPS in the uploaded files.
            val wanted = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
            if (wanted.isEmpty()) startUpload() else uploadPermissions.launch(wanted.toTypedArray())
        }

        override val canManageMedia: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(this@MainActivity)

        override fun requestManageMedia() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
        }
    }

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            SingletonImageLoader.get(this).memoryCache?.clear()
            val saved = res.data?.data
            vm.reload { saved?.let(vm::showInViewer) }
            vm.syncTrunk()
        }
    }

    private val readPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    // Photos are what peek needs; videos are optional, so declining them still opens the gallery.
    private fun hasFullAccess() = ContextCompat.checkSelfPermission(this, readPermissions[0]) == PackageManager.PERMISSION_GRANTED

    private fun hasVideoAccess() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    private fun hasPartialAccess() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PeekTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var full by remember { mutableStateOf(hasFullAccess()) }
                    var partial by remember { mutableStateOf(hasPartialAccess()) }
                    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                        full = hasFullAccess()
                        partial = hasPartialAccess()
                        if (full || partial) vm.reload()
                    }
                    LaunchedEffect(Unit) {
                        if (!full && !partial) {
                            request.launch(requestSet())
                        } else if (full && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasVideoAccess()) {
                            // Installs from before video support only granted photos: ask once for videos too.
                            val prefs = getSharedPreferences("gallery", 0)
                            if (!prefs.getBoolean("askedVideo", false)) {
                                prefs.edit().putBoolean("askedVideo", true).apply()
                                request.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO))
                            }
                        }
                    }
                    LifecycleResumeEffect(full, partial) {
                        full = hasFullAccess()
                        partial = hasPartialAccess()
                        if (full || partial) vm.reload()
                        onPauseOrDispose {}
                    }
                    if (full || partial) {
                        GalleryScreen(vm = vm, ops = ops)
                    } else {
                        PermissionScreen { request.launch(requestSet()) }
                    }
                }
            }
        }
    }

    private fun requestSet(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            readPermissions + Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        } else {
            readPermissions
        }

}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("peek braucht Zugriff auf deine Bilder, um sie anzuzeigen.", textAlign = TextAlign.Center)
        Button(onClick = onRequest) { Text("Zugriff erlauben") }
    }
}
