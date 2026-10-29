package com.xiaomusic.android.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaomusic.android.XiaoMusicApplication
import com.xiaomusic.android.data.PlayMusicRequest
import com.xiaomusic.android.data.ServerConfigManager
import com.xiaomusic.android.ui.theme.TealPrimary
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * 本地曲库状态管理器，切换 Tab 时数据依然保留
 */
class HomeViewModel : ViewModel() {
    var musicMap by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var selectedPlaylist by mutableStateOf("全部")
    var isLoading by mutableStateOf(false)
    var errorMsg by mutableStateOf<String?>(null)
    var hasLoaded by mutableStateOf(false)

    fun loadMusic(config: ServerConfigManager, forceRefresh: Boolean = false) {
        isLoading = true
        errorMsg = null
        viewModelScope.launch {
            try {
                val api = config.getApiService()
                if (forceRefresh) {
                    api.refreshMusicList()
                }
                val map = api.getMusicList()
                musicMap = map
                if (!map.containsKey(selectedPlaylist) && map.isNotEmpty()) {
                    selectedPlaylist = map.keys.first()
                }
                hasLoaded = true
            } catch (e: Exception) {
                errorMsg = "加载歌曲失败: ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val config = XiaoMusicApplication.instance.serverConfig
    val player = XiaoMusicApplication.instance.playerManager
    val scope = rememberCoroutineScope()

    val currentDevice by config.currentDeviceState.collectAsState()

    // 仅在首次进入或列表为空时自动加载
    LaunchedEffect(Unit) {
        if (!viewModel.hasLoaded || viewModel.musicMap.isEmpty()) {
            viewModel.loadMusic(config)
        }
    }

    // 当切换为音箱设备时，自动启动该音箱的实时状态同步
    LaunchedEffect(currentDevice.did) {
        if (!currentDevice.isLocal) {
            player.startDevicePolling(currentDevice.did, currentDevice.name)
        }
    }

    // 播放歌曲（手机本机 或 投送小爱音箱）
    fun playSong(name: String) {
        val currentSongs = viewModel.musicMap[viewModel.selectedPlaylist] ?: emptyList()
        scope.launch {
            try {
                val api = config.getApiService()
                if (config.isLocalPlayback()) {
                    // 1. 本机播放 (当前手机喇叭/耳机)
                    val info = api.getMusicInfo(name)
                    val rawUrl = info.url
                    val fullUrl = if (!rawUrl.isNullOrBlank()) {
                        config.getFullMediaUrl(rawUrl)
                    } else {
                        config.getFullMediaUrl("/music/" + URLEncoder.encode(name, "UTF-8"))
                    }
                    player.playLocal(fullUrl, name, currentSongs)
                    Toast.makeText(context, "▶️ 正在本机播放: $name", Toast.LENGTH_SHORT).show()
                } else {
                    // 2. 投送到小爱音箱播放
                    player.playOnDevice(
                        did = config.selectedDid,
                        deviceName = currentDevice.name,
                        title = name,
                        searchKey = "",
                        playlist = currentSongs
                    )
                    Toast.makeText(
                        context,
                        "🔊 已投送至【${currentDevice.name}】播放:\n$name",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "播放异常: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 下载歌曲到手机本地（使用系统 DownloadManager 保存到公共 Downloads 目录）
    fun downloadSongToPhone(name: String) {
        scope.launch {
            try {
                val api = config.getApiService()
                val info = api.getMusicInfo(name)
                val rawUrl = info.url
                val downloadUrl = if (!rawUrl.isNullOrBlank()) {
                    config.getFullMediaUrl(rawUrl)
                } else {
                    config.getFullMediaUrl("/music/" + URLEncoder.encode(name, "UTF-8"))
                }

                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                if (downloadManager == null) {
                    Toast.makeText(context, "无法启动系统下载管理器", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val fileName = if (name.endsWith(".mp3", ignoreCase = true) ||
                    name.endsWith(".flac", ignoreCase = true) ||
                    name.endsWith(".m4a", ignoreCase = true) ||
                    name.endsWith(".wav", ignoreCase = true)
                ) {
                    name
                } else {
                    "$name.mp3"
                }

                val uri = Uri.parse(downloadUrl)
                val request = DownloadManager.Request(uri).apply {
                    setTitle(fileName)
                    setDescription("XiaoMusic 音乐文件下载")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

                downloadManager.enqueue(request)
                Toast.makeText(
                    context,
                    "📥 已开始下载至手机【下载】目录：\n$fileName\n完成后可在通知栏或文件管理器查看",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, "下载请求失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val currentSongs = viewModel.musicMap[viewModel.selectedPlaylist] ?: emptyList()

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题与当前输出设备胶囊
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "我的本地音乐库",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "共收录 ${viewModel.musicMap.values.flatten().toSet().size} 首本地歌曲",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
                    )
                }

                IconButton(onClick = { viewModel.loadMusic(config, forceRefresh = true) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新并重扫磁盘", tint = TealPrimary)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 当前播放输出设备指示胶囊
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (currentDevice.isLocal) TealPrimary.copy(alpha = 0.12f) else Color(0xFFFFE0B2))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (currentDevice.isLocal) Icons.Default.Smartphone else Icons.Default.Speaker,
                    contentDescription = null,
                    tint = if (currentDevice.isLocal) TealPrimary else Color(0xFFE65100),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (currentDevice.isLocal) "播放输出: 手机本机 (默认)" else "播放输出: 投送至 ${currentDevice.name}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (currentDevice.isLocal) TealPrimary else Color(0xFFE65100)
                )
            }
        }

        // 歌单分类 Tab / 标签栏
        if (viewModel.musicMap.isNotEmpty()) {
            ScrollableTabRow(
                selectedTabIndex = viewModel.musicMap.keys.toList().indexOf(viewModel.selectedPlaylist).coerceAtLeast(0),
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                viewModel.musicMap.keys.forEach { listName ->
                    Tab(
                        selected = viewModel.selectedPlaylist == listName,
                        onClick = { viewModel.selectedPlaylist = listName },
                        text = {
                            Text(
                                text = "$listName (${viewModel.musicMap[listName]?.size ?: 0})",
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }
        }

        if (viewModel.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TealPrimary)
            }
        } else if (viewModel.errorMsg != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = viewModel.errorMsg ?: "", color = Color.Red, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = { viewModel.loadMusic(config, forceRefresh = true) }) {
                        Text("重试并重新扫描磁盘")
                    }
                }
            }
        } else if (currentSongs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "当前歌单暂无歌曲，快去「B站搜索」下载吧~", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(currentSongs) { songName ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { playSong(songName) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = TealPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = songName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            // 操作区：播放 / 投送
                            IconButton(onClick = { playSong(songName) }) {
                                Icon(
                                    imageVector = if (currentDevice.isLocal) Icons.Default.PlayArrow else Icons.Default.Speaker,
                                    contentDescription = "播放",
                                    tint = if (currentDevice.isLocal) TealPrimary else Color(0xFFE65100)
                                )
                            }

                            // 操作区：下载到手机
                            IconButton(onClick = { downloadSongToPhone(songName) }) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "下载到手机",
                                    tint = TealPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
