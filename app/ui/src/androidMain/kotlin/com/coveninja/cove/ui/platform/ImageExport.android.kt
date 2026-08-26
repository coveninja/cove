package com.coveninja.cove.ui.platform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberImageExporter(): ImageExporter? {
    val context = LocalContext.current
    return remember(context) { AndroidImageExporter(context) }
}

/**
 * Hands the image to the share sheet.
 *
 * Android has no "save it here" gesture worth offering from inside an app — the picture is
 * far more likely to be going to a messaging app than to the filesystem — so this shares
 * rather than saves, and says so in [actionLabel].
 *
 * The file goes to a directory inside the cache that only this feature writes to, and the
 * previous export is cleared before each new one. A share sheet hands a URI to another app
 * which may read it whenever it gets round to it, so the file cannot be deleted after the
 * intent is fired; clearing on the way *in* is the version of that which is safe.
 */
private class AndroidImageExporter(private val context: Context) : ImageExporter {

    override val actionLabel: String = "Share"

    override suspend fun export(png: ByteArray, suggestedName: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(context.cacheDir, SHARE_DIRECTORY).apply {
                    deleteRecursively()
                    mkdirs()
                }
                val file = File(directory, suggestedName)
                file.writeBytes(png)

                // Must match the authority in the app manifest's <provider>. Read from the
                // running package rather than hardcoded, so a debug build with a suffixed
                // application id still resolves its own provider.
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    // Without this the receiving app gets a URI it has no permission to
                    // open, and the share silently produces a broken image on the far side.
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // Started from a Context that is not an Activity in some hosts, and an
                // intent without this simply never launches.
                val chooser = Intent.createChooser(share, null)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                true
            }.onFailure {
                println("Cove: could not share the recap image — $it")
            }.getOrDefault(false)
        }

    private companion object {
        const val SHARE_DIRECTORY = "shared-images"
    }
}

actual fun ImageBitmap.encodeToPng(): ByteArray? = runCatching {
    ByteArrayOutputStream().also { stream ->
        asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
    }.toByteArray()
}.onFailure { println("Cove: could not encode the recap image — $it") }.getOrNull()
