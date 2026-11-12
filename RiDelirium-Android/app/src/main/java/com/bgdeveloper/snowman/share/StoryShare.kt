package com.bgdeveloper.snowman.share

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.bgdeveloper.snowman.BuildConfig
import java.io.File

/** Story destinations that accept a background image through an Intent. */
enum class StoryTarget(val title: String, val packageName: String, private val action: String, private val appIdExtra: String) {
    Instagram(
        "Instagram Story", "com.instagram.android",
        "com.instagram.share.ADD_TO_STORY", "source_application",
    ),
    Facebook(
        "Facebook Story", "com.facebook.katana",
        "com.facebook.stories.ADD_TO_STORY", "com.facebook.platform.extra.APPLICATION_ID",
    );

    /** Needs the package in the manifest `<queries>`. */
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun share(context: Context, image: Uri) {
        val intent = Intent(action).apply {
            setDataAndType(image, "image/png")
            setPackage(packageName)
            putExtra(appIdExtra, BuildConfig.FACEBOOK_APP_ID)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.grantUriPermission(packageName, image, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }
}

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

    /** The regular system share sheet, for apps other than the Story targets. */
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
