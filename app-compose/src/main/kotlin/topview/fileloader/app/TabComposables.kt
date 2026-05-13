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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
            Button(onClick = { store.refreshAllBatchStatuses() }, shape = RoundedCornerShape(14.dp)) {
                Text("刷新状态")
            }
            Text("自动刷新", color = colors.onSurfaceVariant)
            RefreshOptionButton("手动刷新", store)
            RefreshOptionButton("每10秒", store)
            RefreshOptionButton("每30秒", store)
            RefreshOptionButton("每1分钟", store)
            RefreshOptionButton("每5分钟", store)
            Text("排序", color = colors.onSurfaceVariant)
            SortOrderButton("最新在上", "newest", store)
            SortOrderButton("最早在上", "oldest", store)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("当前模式: ${store.autoRefreshLabel.value}", color = colors.onSurfaceVariant)
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
            Text("批次ID", modifier = Modifier.weight(0.28f), color = colors.onSurfaceVariant)
            Text("状态", modifier = Modifier.weight(0.38f), color = colors.onSurfaceVariant)
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
                        Text(row.batchId, modifier = Modifier.weight(0.28f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.status, modifier = Modifier.weight(0.38f), maxLines = 2, overflow = TextOverflow.Ellipsis)

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
private fun RefreshOptionButton(label: String, store: ComposeMonitorStore) {
    val active = store.autoRefreshLabel.value == label
    if (active) {
        Button(
            onClick = { store.setAutoRefresh(label) },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = { store.setAutoRefresh(label) },
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(label)
        }
    }
}

@Composable
private fun SortOrderButton(label: String, value: String, store: ComposeMonitorStore) {
    val active = store.sortOrder.value == value
    if (active) {
        Button(
            onClick = { store.sortOrder.value = value },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = { store.sortOrder.value = value },
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(label)
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
