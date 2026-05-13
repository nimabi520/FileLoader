package topview.fileloader.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AnimatedDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(180))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { onDismissRequest() },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(180)) + scaleIn(
                    initialScale = 0.88f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
                exit = fadeOut(tween(120)) + scaleOut(
                    targetScale = 0.92f,
                    animationSpec = tween(120)
                )
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun LoginStatusDialog(store: ComposeMonitorStore) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.width(380.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "登录状态",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (store.isLoggingOut.value) {
                    Text("正在退出登录...", color = colors.onSurfaceVariant)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (store.isCheckingLogin.value) {
                    Text("正在检查登录状态...", color = colors.onSurfaceVariant)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (store.isLoggedIn.value) {
                    LoginInfoLine("用户ID", store.loginUserId.value.ifBlank { "-" })
                    LoginInfoLine("姓名", store.loginUserName.value.ifBlank { "-" })
                    LoginInfoLine("角色", if (store.loginRole.value == 1) "管理员" else "普通用户")
                    LoginInfoLine("Session", store.loginSessionId.value.ifBlank { "-" })
                    LoginInfoLine("登录时间", store.loginTime.value.ifBlank { "-" })
                } else {
                    Text(
                        text = store.loginDialogMessage.value,
                        color = colors.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "请先完成认证登录后再开始监控与上传。",
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = { store.closeLoginDialog() },
                    enabled = !store.isLoggingOut.value
                ) {
                    Text("关闭")
                }
                if (store.isLoggedIn.value) {
                    TextButton(
                        onClick = { store.logout("弹窗退出登录") },
                        enabled = !store.isLoggingOut.value && !store.isCheckingLogin.value
                    ) {
                        Text(if (store.isLoggingOut.value) "退出中..." else "退出登录")
                    }
                } else {
                    TextButton(
                        onClick = {
                            store.closeLoginDialog()
                            store.openEncryptLoginDialog()
                        },
                        enabled = !store.isCheckingLogin.value && !store.isLoggingOut.value
                    ) {
                        Text("去登录")
                    }
                }
                TextButton(
                    onClick = { store.checkLoginStatus("弹窗刷新", openDialogOnUnauthorized = true) },
                    enabled = !store.isCheckingLogin.value && !store.isLoggingOut.value
                ) {
                    Text(if (store.isCheckingLogin.value) "检查中..." else "刷新状态")
                }
            }
        }
    }
}

@Composable
internal fun EncryptLoginDialog(store: ComposeMonitorStore) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.width(380.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "用户登录",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = store.encryptLoginUserIdInput.value,
                    onValueChange = { store.encryptLoginUserIdInput.value = it },
                    label = { Text("用户ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !store.isEncryptingPassword.value
                )
                OutlinedTextField(
                    value = store.encryptLoginPasswordInput.value,
                    onValueChange = { store.encryptLoginPasswordInput.value = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !store.isEncryptingPassword.value
                )
                if (store.encryptLoginErrorMessage.value.isNotBlank()) {
                    Text(
                        text = store.encryptLoginErrorMessage.value,
                        color = colors.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (store.isEncryptingPassword.value) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = { store.closeEncryptLoginDialog() },
                    enabled = !store.isEncryptingPassword.value
                ) {
                    Text("关闭")
                }
                TextButton(
                    onClick = { store.encryptLogin() },
                    enabled = !store.isEncryptingPassword.value
                ) {
                    Text(if (store.isEncryptingPassword.value) "登录中..." else "登录")
                }
            }
        }
    }
}

@Composable
private fun LoginInfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label:",
            modifier = Modifier.width(72.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, modifier = Modifier.weight(1f))
    }
}
