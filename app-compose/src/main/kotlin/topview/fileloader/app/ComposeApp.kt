package topview.fileloader.app

import topview.fileloader.config.AppConfig

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun startComposeApp() = application {
    println("[ComposeMonitorApp] Compose UI started.")
    val store = remember { ComposeMonitorStore() }

    Window(
        title = "多文件夹监控上传工具（Compose）",
        onCloseRequest = {
            store.shutdown()
            exitApplication()
        }
    ) {
        DisposableEffect(Unit) {
            DropTarget(window, object : DropTargetAdapter() {
                override fun dragEnter(dtde: java.awt.dnd.DropTargetDragEvent?) {
                    dtde?.acceptDrag(DnDConstants.ACTION_COPY)
                    store.isDragOver.value = true
                }

                override fun dragExit(dte: java.awt.dnd.DropTargetEvent?) {
                    store.isDragOver.value = false
                }

                override fun drop(dtde: DropTargetDropEvent?) {
                    store.isDragOver.value = false
                    dtde ?: return
                    dtde.acceptDrop(DnDConstants.ACTION_COPY)
                    try {
                        val transferable = dtde.transferable
                        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                            val paths = files?.filterIsInstance<File>()?.map { it.absolutePath } ?: emptyList()
                            if (paths.isNotEmpty()) {
                                store.addFolderFromDrop(paths)
                            }
                        }
                    } catch (e: Exception) {
                        store.addLog("拖拽处理失败: ${e.message}")
                    } finally {
                        dtde.dropComplete(true)
                    }
                }
            })
            onDispose {
                try {
                    window.dropTarget = null
                } catch (_: Exception) {
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                store.shutdown()
            }
        }

        LaunchedEffect(Unit) {
            store.restoreLoginState()
            if (AppConfig.getEncryptedPassword().isBlank()) {
                store.openEncryptLoginDialog()
            }
        }

        FileLoaderTheme {
            ComposeMonitorScreen(store)
        }
    }
}

fun main() = startComposeApp()
