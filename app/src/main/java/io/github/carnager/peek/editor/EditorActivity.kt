package io.github.carnager.peek.editor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.carnager.peek.R
import io.github.carnager.peek.ui.PeekTheme

class EditorActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()
    private var pendingQuality = 92

    private val writeRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) doOverwrite(pendingQuality)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = sourceUri(intent)
        if (uri == null) {
            Toast.makeText(this, getString(R.string.edit_no_image_received), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        vm.load(uri)
        setContent {
            PeekTheme(forceDark = true) {
                // Without a Surface the default content colour is black, which vanishes on the black editor.
                androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color.Black, contentColor = androidx.compose.ui.graphics.Color.White) {
                EditorScreen(
                    vm = vm,
                    onClose = ::finish,
                    onSaveCopy = { format, quality ->
                        vm.runBusy {
                            val out = vm.saveCopy(format, quality)
                            done(out, getString(R.string.edit_saved_as_copy))
                        }
                    },
                    onSaveToTrunk = intent.getLongExtra(EXTRA_TRUNK_ID, 0L).takeIf { it != 0L }?.let { id ->
                        { format, quality ->
                            vm.runBusy {
                                vm.saveEditToTrunk(format, quality, id)
                                Toast.makeText(this, getString(R.string.edit_saved_to_trunk), Toast.LENGTH_SHORT).show()
                                setResult(RESULT_OK)
                                finish()
                            }
                        }
                    },
                    onUploadToTrunk = intent.getLongExtra(EXTRA_TRUNK_ID, 0L).takeIf { it != 0L }?.let {
                        { format, quality ->
                            vm.runBusy {
                                vm.uploadToTrunk(format, quality, intent.getStringExtra(EXTRA_TRUNK_NAME) ?: "bild.jpg", intent.getLongExtra(EXTRA_TRUNK_TAKEN, 0L))
                                Toast.makeText(this, getString(R.string.edit_uploaded_copy_to_trunk), Toast.LENGTH_SHORT).show()
                                setResult(RESULT_OK)
                                finish()
                            }
                        }
                    },
                    onOverwrite = { quality ->
                        val src = vm.source ?: return@EditorScreen
                        pendingQuality = quality
                        if (src.isMediaStore) {
                            // Files from other apps need the user's consent before we may change them.
                            val pi = MediaStore.createWriteRequest(contentResolver, listOf(src.uri))
                            writeRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        } else {
                            doOverwrite(quality)
                        }
                    },
                )
                }
            }
        }
    }

    private fun doOverwrite(quality: Int) {
        vm.runBusy {
            try {
                vm.overwrite(quality)
            } catch (e: SecurityException) {
                throw IllegalStateException(getString(R.string.edit_no_write_permission))
            }
            done(vm.source!!.uri, getString(R.string.edit_original_replaced))
        }
    }

    private fun done(uri: Uri, message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, Intent().setData(uri))
        finish()
    }

    private fun sourceUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> intent.data
    }

    companion object {
        const val EXTRA_TRUNK_ID = "trunkId"
        const val EXTRA_TRUNK_NAME = "trunkName"
        const val EXTRA_TRUNK_TAKEN = "trunkTaken"

        fun intent(activity: Activity, uri: Uri): Intent =
            Intent(activity, EditorActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        /** For a photo from trunk, opened from its downloaded original. */
        fun trunkIntent(activity: Activity, uri: Uri, asset: io.github.carnager.peek.trunk.RemoteAsset): Intent =
            intent(activity, uri)
                .putExtra(EXTRA_TRUNK_ID, asset.id)
                .putExtra(EXTRA_TRUNK_NAME, asset.name)
                .putExtra(EXTRA_TRUNK_TAKEN, asset.takenAt)
    }
}
