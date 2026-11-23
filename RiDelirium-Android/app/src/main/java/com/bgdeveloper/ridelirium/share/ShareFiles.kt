package com.bgdeveloper.ridelirium.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.bgdeveloper.ridelirium.BuildConfig
import java.io.File

object ShareFiles {
    /** Writes into cache/share, which the FileProvider exposes. */
    fun save(context: Context, name: String, write: (java.io.OutputStream) -> Unit): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use(write)
        return FileProvider.getUriForFile(context, BuildConfig.FILE_PROVIDER_AUTHORITY, file)
    }

    fun savePng(context: Context, name: String, bitmap: Bitmap): Uri =
        save(context, name) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** The regular system share sheet. */
    fun chooser(context: Context, uri: Uri, mime: String, text: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
