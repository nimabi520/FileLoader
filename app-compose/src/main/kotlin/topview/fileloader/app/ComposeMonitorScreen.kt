@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package topview.fileloader.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

import java.nio.file.Paths

@Composable
internal fun ComposeMonitorScreen(store: ComposeMonitorStore) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("监控记录", "批次状态", "系统日志")
    val colors = MaterialTheme.colorScheme
    val background = remember(colors) {
        Brush.verticalGradient(
            colors = listOf(
                colors.background,
                colors.surfaceVariant.copy(alpha = 0.35f),
                colors.background
            )
        )
    }

    LaunchedEffect(store.pendingSnackbarMessage.value) {
        store.pendingSnackbarMessage.value?.let { message ->
            store.snackbarHostState.showSnackbar(message)
            store.pendingSnackbarMessage.value = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(store.snackbarHostState) }
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.94f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Text("FileLoader", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "简洁、稳定、现代化的自动上传面板",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (store.isCheckingLogin.value) {
                                "登录状态: 检查中..."
                            } else if (store.isLoggedIn.value) {
                                val display = if (store.loginUserName.value.isNotBlank()) {
                                    "${store.loginUserName.value} (${store.loginUserId.value.ifBlank { "未知用户" }})"
                                } else {
                                    store.loginUserId.value.ifBlank { "未知用户" }
                                }
                                "登录状态: 已登录 · $display"
                            } else {
                                "登录状态: 未登录"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (store.isLoggedIn.value) colors.secondary else colors.error
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = store.folderInput.value,
                                onValueChange = { store.folderInput.value = it },
                                label = { Text("文件夹路径/拖动要上传的文件夹到窗口") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedButton(
                                onClick = { store.browseFolder() },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("浏览")
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Button(
                                onClick = { store.addFolderFromInput() },
                                enabled = store.isLoggedIn.value,
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("添加文件夹")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    store.openLoginDialog()
                                    store.checkLoginStatus("手动检查", openDialogOnUnauthorized = true)
                                },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    if (store.isCheckingLogin.value) "登录状态: 检查中"
                                    else if (store.isLoggedIn.value) "登录状态: 已登录"
                                    else "登录状态: 未登录"
                                )
                            }
                            OutlinedButton(
                                onClick = { store.removeSelectedFolder() },
                                enabled = store.selectedFolder.value != null,
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("移除选中")
                            }
                            Button(
                                onClick = { store.openConfig() },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors()
                            ) {
                                Text("配置")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.95f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "监控文件夹",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.titleMedium
                            )
                            HorizontalDivider(color = colors.outline.copy(alpha = 0.25f))

                            if (store.monitoredFoldersUi.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("暂无监控文件夹", color = colors.onSurfaceVariant)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(store.monitoredFoldersUi) { folder ->
                                        val selected = store.selectedFolder.value == folder
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = if (selected) colors.secondaryContainer else colors.surface,
                                            border = BorderStroke(
                                                width = 1.dp,
                                                color = if (selected) colors.secondary else colors.outline.copy(alpha = 0.25f)
                                            ),
                                            tonalElevation = if (selected) 2.dp else 0.dp,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { store.selectedFolder.value = folder }
                                                .onPointerEvent(PointerEventType.Press) { event ->
                                                    if (event.button == PointerButton.Secondary) {
                                                        val change = event.changes.first()
                                                        store.contextMenuOffset.value = change.position
                                                        store.contextMenuFolder.value = folder
                                                        store.showFolderContextMenu.value = true
                                                    }
                                                }
                                        ) {
                                            Box(modifier = Modifier.fillMaxWidth()) {
                                                Text(
                                                    text = folder,
                                                    modifier = Modifier
                                                        .padding(horizontal = 12.dp, vertical = 11.dp)
                                                        .fillMaxWidth(),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                                val density = LocalDensity.current
                                                DropdownMenu(
                                                    expanded = store.showFolderContextMenu.value && store.contextMenuFolder.value == folder,
                                                    onDismissRequest = { store.showFolderContextMenu.value = false },
                                                    offset = with(density) {
                                                        DpOffset(
                                                            store.contextMenuOffset.value.x.toDp(),
                                                            store.contextMenuOffset.value.y.toDp()
                                                        )
                                                    }
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("打开目录") },
                                                        onClick = {
                                                            store.showFolderContextMenu.value = false
                                                            store.openFolderInExplorer(folder)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("立即扫描上传") },
                                                        onClick = {
                                                            store.showFolderContextMenu.value = false
                                                            store.triggerFolderUpload(folder)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("移除监控") },
                                                        onClick = {
                                                            store.showFolderContextMenu.value = false
                                                            store.removeFolder(Paths.get(folder))
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.95f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            TabRow(
                                selectedTabIndex = selectedTab,
                                containerColor = Color.Transparent,
                                divider = {
                                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))
                                }
                            ) {
                                tabs.forEachIndexed { index, title ->
                                    Tab(
                                        selected = selectedTab == index,
                                        onClick = { selectedTab = index },
                                        text = { Text(title) }
                                    )
                                }
                            }

                            when (selectedTab) {
                                0 -> UploadRecordsTab(store)
                                1 -> BatchStatusTab(store)
                                else -> LogTab(store)
                            }
                        }
                    }
                }

                if (store.progressMap.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.95f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text("上传进度", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(10.dp))

                            store.progressMap.toSortedMap().forEach { (fileName, progress) ->
                                Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (progress < 0) {
                                        LinearProgressIndicator(modifier = Modifier.weight(1f))
                                    } else {
                                        val value = progress.coerceIn(0, 100) / 100f
                                        LinearProgressIndicator(progress = { value }, modifier = Modifier.weight(1f))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (progress < 0) "..." else "$progress%")
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surface.copy(alpha = 0.9f),
                    tonalElevation = 1.dp
                ) {
                    Text(
                        text = store.statusText.value,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            AnimatedDialog(
                visible = store.showLoginDialog.value,
                onDismissRequest = { store.closeLoginDialog() }
            ) {
                LoginStatusDialog(store)
            }

            AnimatedDialog(
                visible = store.showEncryptLoginDialog.value,
                onDismissRequest = { store.closeEncryptLoginDialog() }
            ) {
                EncryptLoginDialog(store)
            }

            if (store.isDragOver.value) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "拖拽文件夹到此处添加监控",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            AnimatedVisibility(
                visible = store.showSettingsPanel.value,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { store.closeSettingsPanel() }
                )
            }

            AnimatedVisibility(
                visible = store.showSettingsPanel.value,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                SettingsPanel(store)
            }
        }
    }
}
