package ir.balasari.bikeodometer

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object FileShare {
    /** فایل را در cache می‌نویسد و با FileProvider برای اشتراک‌گذاری آماده می‌کند. */
    fun createUri(context: Context, filename: String, content: String, bom: Boolean = true): Uri? = try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, filename)
        // BOM تا اکسل فارسی را درست نشان دهد (برای GPX لازم نیست)
        file.writeText((if (bom) "\uFEFF" else "") + content, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    } catch (_: Exception) {
        null
    }
}
