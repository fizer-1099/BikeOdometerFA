package ir.balasari.bikeodometer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.os.Build

object ContentProvider {
    fun createContentUriCompat(context: Context, filename: String, content: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                uri
            } else {
                val file = java.io.File(context.getExternalFilesDir(null), filename)
                file.writeText(content, Charsets.UTF_8)
                androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".provider", file)
            }
        } catch (_: Exception) { null }
    }
}
