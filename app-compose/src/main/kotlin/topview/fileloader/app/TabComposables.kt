package topview.fileloader.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.TouchApp

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun UploadRecordsTab(store: ComposeMonitorStore) {
    if (store.uploadRecords.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无上传记录")
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val colors = MaterialTheme.colorScheme

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("时间", modifier = Modifier.weight(0.28f), color = colors.onSurfaceVariant)
            Text("文件名", modifier = Modifier.weight(0.30f), color = colors.onSurfaceVariant)
            Text("批次ID", modifier = Modifier.weight(0.25f), color = colors.onSurfaceVariant)
            Text("状态", modifier = Modifier.weight(0.17f), color = colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(store.uploadRecords) { row ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.2f)),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(row.time, modifier = Modifier.weight(0.28f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.fileName, modifier = Modifier.weight(0.30f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.batchId, modifier = Modifier.weight(0.25f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.status, modifier = Modifier.weight(0.17f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun BatchStatusTab(store: ComposeMonitorStore) {
    val colors = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { store.refreshAllBatchStatuses() },
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.height(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("刷新状态")
            }

            Box {
                OutlinedButton(
                    onClick = { store.showRefreshMenu.value = true },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(store.autoRefreshLabel.value, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.height(18.dp))
                }
                DropdownMenu(
                    expanded = store.showRefreshMenu.value,
                    onDismissRequest = { store.showRefreshMenu.value = false }
                ) {
                    val refreshOptions = listOf(
                        "手动刷新" to Icons.Default.TouchApp,
                        "每10秒" to Icons.Default.Schedule,
                        "每30秒" to Icons.Default.Schedule,
                        "每1分钟" to Icons.Default.Schedule,
                        "每5分钟" to Icons.Default.Schedule
                    )
                    refreshOptions.forEach { (label, icon) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.height(18.dp)) },
                            trailingIcon = {
                                if (store.autoRefreshLabel.value == label) {
                                    Icon(Icons.Default.Check, contentDescription = "已选择", modifier = Modifier.height(18.dp))
                                }
                            },
                            onClick = {
                                store.setAutoRefresh(label)
                                store.showRefreshMenu.value = false
                            }
                        )
                    }
                }
            }

            Box {
                OutlinedButton(
                    onClick = { store.showSortMenu.value = true },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (store.sortOrder.value == "newest") "最新在上" else "最早在上", fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.height(18.dp))
                }
                DropdownMenu(
                    expanded = store.showSortMenu.value,
                    onDismissRequest = { store.showSortMenu.value = false }
                ) {
                    val sortOptions = listOf("最新在上" to "newest", "最早在上" to "oldest")
                    sortOptions.forEach { (label, value) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.height(18.dp)) },
                            trailingIcon = {
                                if (store.sortOrder.value == value) {
                                    Icon(Icons.Default.Check, contentDescription = "已选择", modifier = Modifier.height(18.dp))
                                }
                            },
                            onClick = {
                                store.sortOrder.value = value
                                store.showSortMenu.value = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (store.batchStatuses.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无批次记录")
            }
            return
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text("上传时间", modifier = Modifier.weight(0.22f), color = colors.onSurfaceVariant)
            Text("批次ID", modifier = Modifier.weight(0.26f), color = colors.onSurfaceVariant)
            Text("状态", modifier = Modifier.weight(0.35f), color = colors.onSurfaceVariant)
            Text("下载报告", modifier = Modifier.weight(0.17f), color = colors.onSurfaceVariant)
            Text("下载异常", modifier = Modifier.weight(0.17f), color = colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sorted = if (store.sortOrder.value == "newest") {
                store.batchStatuses.sortedByDescending { it.createdAt }
            } else {
                store.batchStatuses.sortedBy { it.createdAt }
            }
            items(sorted) { row ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (row.downloadable) colors.secondaryContainer.copy(alpha = 0.45f) else colors.surface,
                    border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.2f)),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(row.createdAt, modifier = Modifier.weight(0.22f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        Text(row.batchId, modifier = Modifier.weight(0.26f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.status, modifier = Modifier.weight(0.35f), maxLines = 2, overflow = TextOverflow.Ellipsis)

                        Box(modifier = Modifier.weight(0.17f), contentAlignment = Alignment.CenterStart) {
                            Button(
                                onClick = { store.downloadBatchResult(row.batchId) },
                                enabled = row.downloadable,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("下载")
                            }
                        }

                        Box(modifier = Modifier.weight(0.17f), contentAlignment = Alignment.CenterStart) {
                            OutlinedButton(
                                onClick = { store.downloadBatchWrong(row.batchId) },
                                enabled = row.downloadable,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("下载")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LogTab(store: ComposeMonitorStore) {
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .background(Color.Transparent)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.2f))
        ) {
            if (store.logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无日志", color = colors.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(store.logs) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, color = colors.onSurface)
                    }
                }
            }
        }
    }
}
