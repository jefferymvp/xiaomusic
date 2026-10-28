package com.xiaomusic.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomusic.android.XiaoMusicApplication
import com.xiaomusic.android.data.PlaybackDevice
import com.xiaomusic.android.ui.theme.TealPrimary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val configManager = XiaoMusicApplication.instance.serverConfig
    val scope = rememberCoroutineScope()

    var hostInput by remember { mutableStateOf(configManager.host) }
    var portInput by remember { mutableStateOf(configManager.port.toString()) }

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }

    // 音箱设备列表
    var deviceList by remember { mutableStateOf<List<PlaybackDevice>>(emptyList()) }
    var isLoadingDevices by remember { mutableStateOf(false) }
    val currentSelectedDevice by configManager.currentDeviceState.collectAsState()

    fun loadDevices() {
        isLoadingDevices = true
        scope.launch {
            try {
                deviceList = configManager.fetchDevicesFromServer()
            } catch (e: Exception) {
                // 保持已加载
            } finally {
                isLoadingDevices = false
            }
        }
    }

    fun testConnection() {
        val port = portInput.toIntOrNull() ?: 58090
        configManager.host = hostInput.trim()
        configManager.port = port

        isTesting = true
        testStatus = "正在测试连接 ${configManager.baseUrl} ..."
        scope.launch {
            try {
                val api = configManager.getApiService()
                val versionRes = api.getVersion()
                val ver = versionRes.version ?: "0.6.1"
                isSuccess = true
                testStatus = "✅ 连接成功！服务端 XiaoMusic 版本: v$ver"
                // 连接成功后自动刷新音箱设备列表
                loadDevices()
            } catch (e: Exception) {
                isSuccess = false
                testStatus = "❌ 连接失败: ${e.localizedMessage ?: "网络超时或地址不正确"}"
            } finally {
                isTesting = false
            }
        }
    }

    LaunchedEffect(Unit) {
        testConnection()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "服务端连接配置",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = "设置运行 XiaoMusic Python 服务的局域网 IP 与端口",
            style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray),
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        OutlinedTextField(
            value = hostInput,
            onValueChange = { hostInput = it },
            label = { Text("服务端 IP / 主机名") },
            placeholder = { Text("例如 192.168.6.58") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = portInput,
            onValueChange = { portInput = it },
            label = { Text("服务端口 (Port)") },
            placeholder = { Text("例如 58090") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { testConnection() },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp),
            enabled = !isTesting
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在验证...")
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存配置并测试连接")
            }
        }

        testStatus?.let { status ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                            fontSize = 13.sp
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // ================= 播放输出设备区域 (与网页端一致) =================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "🔊 播放输出设备",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "可选择手机本机发声，或投送到已绑定的任何小爱音箱",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
                )
            }
            IconButton(onClick = { loadDevices() }, enabled = !isLoadingDevices) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新设备", tint = TealPrimary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoadingDevices) {
            Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = TealPrimary)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                deviceList.forEach { device ->
                    val isSelected = (device.did == currentSelectedDevice.did)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                configManager.selectDevice(device)
                                Toast.makeText(
                                    context,
                                    if (device.isLocal) "已切换为【本机播放】" else "已切换为【${device.name}】投送播放",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) TealPrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
                        ),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, TealPrimary) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (device.isLocal) Icons.Default.Smartphone else Icons.Default.Speaker,
                                contentDescription = null,
                                tint = if (isSelected) TealPrimary else Color.Gray,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) TealPrimary else Color.Unspecified
                                    )
                                )
                                Text(
                                    text = if (device.isLocal) "使用手机自带喇叭或耳机播放 (默认)" else "DID: ${device.did} · 局域网协议投送",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = Color.Gray)
                                )
                            }
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    configManager.selectDevice(device)
                                    Toast.makeText(
                                        context,
                                        if (device.isLocal) "已切换为【本机播放】" else "已切换为【${device.name}】投送播放",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}
