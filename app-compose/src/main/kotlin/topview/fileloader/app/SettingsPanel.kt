package topview.fileloader.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsPanel(store: ComposeMonitorStore) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .width(420.dp)
            .fillMaxHeight(),
        color = colors.surface,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("编辑配置", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { store.closeSettingsPanel() }) {
                    Text("✕")
                }
            }
            HorizontalDivider(color = colors.outline.copy(alpha = 0.25f))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = store.serverUrlInput.value,
                    onValueChange = { store.serverUrlInput.value = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = store.connectTimeoutInput.value,
                    onValueChange = { store.connectTimeoutInput.value = it },
                    label = { Text("连接超时(秒)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = store.readTimeoutInput.value,
                    onValueChange = { store.readTimeoutInput.value = it },
                    label = { Text("读超时(秒)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = store.onlyPdfInput.value,
                        onCheckedChange = { store.onlyPdfInput.value = it }
                    )
                    Text("仅上传 PDF 文件")
                }
            }

            HorizontalDivider(color = colors.outline.copy(alpha = 0.25f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { store.closeSettingsPanel() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val err = store.saveConfigFromDialog()
                        if (err != null) {
                            store.showSnackbar("配置保存失败: $err")
                        } else {
                            store.closeSettingsPanel()
                            store.showSnackbar("配置已保存")
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("保存")
                }
            }
        }
    }
}
