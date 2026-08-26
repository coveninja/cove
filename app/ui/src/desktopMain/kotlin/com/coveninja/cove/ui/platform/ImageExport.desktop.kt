package com.coveninja.cove.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.Window
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@Composable
actual fun rememberImageExporter(): ImageExporter? = remember { DesktopImageExporter }

/**
 * Writes the image wherever the viewer points.
 *
 * Uses Swing's chooser under the **cross-platform** look and feel, exactly as
 * `chooseSubtitleFile` does, and for the same reason: `java.awt.FileDialog` loads the native
 * GTK chooser into this JVM on Linux regardless of the desktop or the look and feel, and it
 * collides with the JVM's own freetype — every widget fails to draw with "error occurred in
 * libfreetype" and what opens is unusable. That is already documented next to the subtitle
 * chooser and must not be re-learned here.
 */
private object DesktopImageExporter : ImageExporter {

    override val actionLabel: String = "Save image"

    override suspend fun export(png: ByteArray, suggestedName: String): Boolean =
        // The chooser is modal and blocks whichever thread runs it; on the main dispatcher
        // that thread is the one drawing the app behind it.
        withContext(Dispatchers.IO) {
            val target = chooseTarget(suggestedName) ?: return@withContext false
            runCatching { target.writeBytes(png) }
                .onFailure { println("Cove: could not write $target — ${it.message}") }
                .isSuccess
        }

    private fun chooseTarget(suggestedName: String): File? {
        val previous = UIManager.getLookAndFeel()
        val swapped = runCatching {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())
        }.isSuccess
        try {
            val chooser = JFileChooser().apply {
                dialogTitle = "Save your year"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter("PNG image", "png")
                selectedFile = File(suggestedName)
            }
            // The chooser can be constructed before the swap reaches it in some orders.
            runCatching { SwingUtilities.updateComponentTreeUI(chooser) }
            val owner: Window? = null
            if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return null
            val chosen = chooser.selectedFile ?: return null
            // The filter constrains what is listed, never what gets typed, so a viewer who
            // types a bare name would otherwise end up with an extensionless file that
            // nothing opens by double-click.
            return if (chosen.name.endsWith(".png", ignoreCase = true)) {
                chosen
            } else {
                File(chosen.parentFile, chosen.name + ".png")
            }
        } finally {
            if (swapped) runCatching { UIManager.setLookAndFeel(previous) }
        }
    }
}

actual fun ImageBitmap.encodeToPng(): ByteArray? = runCatching {
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
}.onFailure { println("Cove: could not encode the recap image — $it") }.getOrNull()
