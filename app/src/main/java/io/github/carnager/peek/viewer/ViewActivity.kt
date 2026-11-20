package io.github.carnager.peek.viewer

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import io.github.carnager.peek.editor.EditorActivity
import io.github.carnager.peek.ui.PeekTheme

/** Shows a single image handed over by another app. */
class ViewActivity : ComponentActivity() {
    private var version by mutableLongStateOf(0L)

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) version++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: return finish()
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
            }
        }.getOrNull()
        val mime = intent.type ?: contentResolver.getType(uri) ?: "image/*"
        setContent {
            PeekTheme(forceDark = true) {
                ImageViewer(
                    items = listOf(
                        ViewerItem(
                            uri = uri,
                            name = name?.first ?: uri.lastPathSegment ?: "",
                            mimeType = mime,
                            size = name?.second ?: 0,
                            version = version,
                        )
                    ),
                    startIndex = 0,
                    onClose = ::finish,
                    onEdit = { editor.launch(EditorActivity.intent(this, it.uri)) },
                    onDelete = null,
                )
            }
        }
    }
}
