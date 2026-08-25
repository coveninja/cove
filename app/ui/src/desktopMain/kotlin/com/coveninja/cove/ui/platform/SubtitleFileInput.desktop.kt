package com.coveninja.cove.ui.platform

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draganddrop.dragData
import com.coveninja.cove.ui.state.SUBTITLE_FILE_EXTENSIONS
import com.coveninja.cove.ui.state.isSubtitleFile
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.io.FilenameFilter
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

actual val canLoadSubtitleFile: Boolean = true

/**
 * A file chooser, owned by whichever window has focus.
 *
 * Owned rather than opened unowned on purpose: a modal dialog with no owner can be
 * raised behind a fullscreen window, where it is invisible and holds the keyboard, so
 * the player simply stops responding.
 */
actual fun chooseSubtitleFile(): String? {
    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    return if (System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true)) {
        chooseWithSwing(owner)
    } else {
        chooseWithAwt(owner as? Frame)
    }
}

/**
 * AWT's own chooser, which on Windows and macOS is the real native one.
 *
 * Not used on Linux, where AWT implements the same call by loading the **native GTK**
 * chooser into this JVM — regardless of look and feel, and regardless of whether the
 * desktop is GTK at all. The JVM already has a freetype of its own and the two collide:
 * every widget fails to draw with "error occurred in libfreetype" and what opens is an
 * unusable window. Observed on Hyprland with a stock Amazon Corretto 21. There is no way
 * to ask AWT for a working dialog there, so Linux gets [chooseWithSwing] instead.
 */
private fun chooseWithAwt(owner: Frame?): String? {
    val dialog = FileDialog(owner, "Choose a subtitle file", FileDialog.LOAD)
    // Honoured on X11 and ignored on Windows, so the answer is checked again below
    // rather than trusted to the filter.
    dialog.filenameFilter = FilenameFilter { _, name -> isSubtitleFile(name) }
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(directory, name).absolutePath.takeIf(::isSubtitleFile)
}

/**
 * Swing's chooser, drawn by Java 2D from top to bottom.
 *
 * Built under the cross-platform look and feel rather than the ambient one. That is
 * defence rather than taste: `configureSwingGlobalsForCompose` sets the *system* look and
 * feel for the whole application, and while Java resolves that to Metal on most Linux
 * desktops, on GNOME it resolves to the GTK one, which paints through native GTK — the
 * same road that makes AWT's dialog above unusable. Metal is Java 2D throughout and has
 * nothing to collide with. The previous look and feel goes back afterwards so nothing
 * else in the process sees the swap.
 */
private fun chooseWithSwing(owner: Window?): String? {
    val previous = UIManager.getLookAndFeel()
    val swapped = runCatching {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())
    }.isSuccess
    try {
        val chooser = JFileChooser().apply {
            dialogTitle = "Choose a subtitle file"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = true
            fileFilter = FileNameExtensionFilter(
                "Subtitle files",
                *SUBTITLE_FILE_EXTENSIONS.toTypedArray(),
            )
        }
        // The chooser was constructed before the swap could reach it in some orders.
        runCatching { SwingUtilities.updateComponentTreeUI(chooser) }
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.absolutePath?.takeIf(::isSubtitleFile)
    } finally {
        if (swapped) runCatching { UIManager.setLookAndFeel(previous) }
    }
}

@Composable
actual fun Modifier.subtitleFileDropTarget(
    onDragChange: (Boolean) -> Unit,
    onFiles: (List<String>) -> Unit,
): Modifier {
    val dragChanged by rememberUpdatedState(onDragChange)
    val filesDropped by rememberUpdatedState(onFiles)
    // Remembered, and reading the callbacks indirectly, because the modifier re-delegates
    // its node whenever the target is not the same object as last time — which for an
    // object rebuilt on every recomposition means the drag in progress ends there.
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = dragChanged(true)
            override fun onExited(event: DragAndDropEvent) = dragChanged(false)
            override fun onEnded(event: DragAndDropEvent) = dragChanged(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragChanged(false)
                val paths = event.localPaths()
                println("Cove: subtitle drop carried ${paths.size} path(s) $paths")
                // Answered even when nothing usable came through. A drop that produces
                // no reply at all is the one outcome the viewer cannot act on: it looks
                // identical to a feature that is not there.
                filesDropped(paths)
                return true
            }
        }
    }
    return dragAndDropTarget(
        // Only inspects which flavours the transferable offers, never reads it: on X11
        // the data itself is not available until the drop actually happens.
        shouldStartDragAndDrop = { it.carriesFiles() },
        target = target,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.carriesFiles(): Boolean {
    val accepted = when (runCatching { dragData() }.getOrNull()) {
        is DragData.FilesList -> true
        // A file drag does not always arrive as a file list. Several Linux sources offer
        // only a text/uri-list, and some only plain text holding a file:// URI — and the
        // text cannot be read here to tell which, because on X11 the data does not exist
        // until the drop. So any text starts a session and the drop decides, which costs
        // an invitation panel over a dragged word and buys a file drop that is not
        // rejected before anyone can see it was offered.
        is DragData.Text -> true
        else -> false
    }
    // The one account a report of "I dropped a file and nothing happened" can carry:
    // without it there is no way to tell a drag this rejected from one the window never
    // received at all.
    println("Cove: subtitle drop target ${if (accepted) "accepted" else "ignored"} a drag [${flavours()}]")
    return accepted
}

/** The flavours on offer, which is safe to ask for while a drag is still in the air. */
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.flavours(): String =
    runCatching { awtTransferable.transferDataFlavors.joinToString(", ") { it.mimeType } }
        .getOrElse { "unreadable: ${it.message}" }

/**
 * The dropped files as absolute local paths, from whichever flavour will give them up.
 *
 * Three routes, because the obvious one is not enough. A KDE file manager on Wayland
 * hands files to an XWayland window through the XDG desktop portal — the drop advertises
 * `application/vnd.portal.filetransfer` alongside the ordinary flavours — and AWT's
 * conversion to a `java.util.List` of `File` then yields **nothing at all** even though
 * `application/x-java-file-list` is on offer and the drag was accepted. The `text/uri-list`
 * the same drop carries is plain and readable, so it is asked next, and any text last.
 *
 * Each failure is logged rather than swallowed: a drop that produces no path is exactly
 * the report that cannot be diagnosed after the fact.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.localPaths(): List<String> {
    val transferable = runCatching { awtTransferable }.getOrNull() ?: return emptyList()

    val fromFileList = transferable.read("file list") {
        (getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
            .orEmpty()
            .filterIsInstance<File>()
            .map(File::getAbsolutePath)
    }.orEmpty()
    if (fromFileList.isNotEmpty()) return fromFileList

    val fromUriList = transferable.read("uri list") {
        (getTransferData(URI_LIST_FLAVOR) as? String).orEmpty().lines()
    }.orEmpty().mapNotNull(::localPath)
    if (fromUriList.isNotEmpty()) return fromUriList

    return transferable.read("text") {
        val flavor = DataFlavor.selectBestTextFlavor(transferDataFlavors)
            ?: return@read emptyList()
        flavor.getReaderForText(this).readText().lines()
    }.orEmpty().mapNotNull(::localPath)
}

/** `text/uri-list` as a plain String, which is the form every source seems willing to give. */
private val URI_LIST_FLAVOR: DataFlavor =
    DataFlavor("text/uri-list;class=java.lang.String", "URI list")

private fun <T> Transferable.read(what: String, block: Transferable.() -> List<T>): List<T>? =
    runCatching { block() }
        .onFailure { println("Cove: subtitle drop could not read the $what — $it") }
        .onSuccess { if (it.isEmpty()) println("Cove: subtitle drop found an empty $what") }
        .getOrNull()

private fun localPath(value: String): String? {
    // A text/uri-list carries comment lines, and a drop that came through as text can
    // arrive with a trailing blank one.
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    if (trimmed.startsWith("file:")) {
        return runCatching { File(URI(trimmed)).absolutePath }.getOrNull()
    }
    // A plain path only counts if something is actually there; otherwise any dragged
    // sentence would be handed on as a filename.
    return runCatching { File(trimmed).takeIf(File::isFile)?.absolutePath }.getOrNull()
}
