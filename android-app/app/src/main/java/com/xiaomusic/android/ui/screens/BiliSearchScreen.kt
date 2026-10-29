package com.xiaomusic.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xiaomusic.android.XiaoMusicApplication
import com.xiaomusic.android.data.BiliVideoItem
import com.xiaomusic.android.data.DownloadMusicRequest
import com.xiaomusic.android.data.PlayMusicRequest
import com.xiaomusic.android.data.ServerConfigManager
import com.xiaomusic.android.ui.theme.BiliPink
import com.xiaomusic.android.ui.theme.TealPrimary
import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DownloadUiState(
    val isDownloading: Boolean = false,
    val progress: Int = 0,
    val statusText: String = "",
    val isCompleted: Boolean = false,
    val isError: Boolean = false
)

/**
 * 维持搜索结果与下载进度的 ViewModel，切 Tab 时不丢失数据
 */
class BiliSearchViewModel : ViewModel() {
    var keyword by mutableStateOf("")
    var searchResults by mutableStateOf<List<BiliVideoItem>>(emptyList())
    var isSearching by mutableStateOf(false)
    var errorMsg by mutableStateOf<String?>(null)
    var isDirectMatch by mutableStateOf(false)
    var hasSearched by mutableStateOf(false)

    // 记录每个 bvid 对应的下载进度状态
    val downloadStates = mutableStateMapOf<String, DownloadUiState>()

    fun doSearch(config: ServerConfigManager) {
        val query = keyword.trim()
        if (query.isEmpty()) return
        isSearching = true
        errorMsg = null
        isDirectMatch = false
        hasSearched = true

        viewModelScope.launch {
            try {
                val api = config.getApiService()
                val res = api.searchBilibili(query)
                if (res.success) {
                    searchResults = res.data
                    isDirectMatch = res.isDirect
                } else {
                    errorMsg = res.error ?: "未检索到相关内容"
                }
            } catch (e: Exception) {
                errorMsg = "搜索发生异常: ${e.localizedMessage}"
            } finally {
                isSearching = false
            }
        }
    }

    fun startDownload(item: BiliVideoItem, config: ServerConfigManager) {
        val bvid = item.bvid
        downloadStates[bvid] = DownloadUiState(
            isDownloading = true,
            progress = 10,
            statusText = "⏳ 正在请求服务端下载任务..."
        )

        viewModelScope.launch {
            try {
                val api = config.getApiService()
                val req = DownloadMusicRequest(
                    url = item.url,
                    name = item.title
                )
                val resp = api.downloadMusic(req)
                if (resp.ret != "OK") {
                    downloadStates[bvid] = DownloadUiState(
                        isDownloading = false,
                        isError = true,
                        statusText = "❌ 下载失败: ${resp.ret}"
                    )
                    return@launch
                }

                // 轮询下载进度
                val taskId = resp.taskId
                if (taskId.isNullOrEmpty()) {
                    downloadStates[bvid] = DownloadUiState(
                        isDownloading = false,
                        isCompleted = true,
                        progress = 100,
                        statusText = "✅ 下载任务已提交"
                    )
                    return@launch
                }

                downloadStates[bvid] = DownloadUiState(
                    isDownloading = true,
                    progress = 25,
                    statusText = "⚡ 后端正在解析并下载音频 (25%)..."
                )

                while (isActive) {
                    delay(1200)
                    try {
                        val progressResp = api.getDownloadProgress(taskId)
                        val p = progressResp.progress
                        val isDone = progressResp.status == "completed" || progressResp.status == "success" || p >= 100
                        val isFail = progressResp.status == "failed" || progressResp.status == "error"

                        if (isDone) {
                            downloadStates[bvid] = DownloadUiState(
                                isDownloading = false,
                                isCompleted = true,
                                progress = 100,
                                statusText = "✅ 下载成功并已存入曲库！"
                            )
                            break
                        } else if (isFail) {
                            downloadStates[bvid] = DownloadUiState(
                                isDownloading = false,
                                isError = true,
                                progress = p,
                                statusText = "❌ 下载失败: ${progressResp.status}"
                            )
                            break
                        } else {
                            val displayProgress = if (p in 1..99) p else 50
                            downloadStates[bvid] = DownloadUiState(
                                isDownloading = true,
                                progress = displayProgress,
                                statusText = "⏳ 正在下载中... ($displayProgress%)"
                            )
                        }
                    } catch (e: Exception) {
                        // 忽略偶发网络轮询异常
                    }
                }
            } catch (e: Exception) {
                downloadStates[bvid] = DownloadUiState(
                    isDownloading = false,
                    isError = true,
                    statusText = "❌ 请求错误: ${e.localizedMessage}"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliSearchScreen(viewModel: BiliSearchViewModel = viewModel()) {
    val context = LocalContext.current
    val config = XiaoMusicApplication.instance.serverConfig
    val player = XiaoMusicApplication.instance.playerManager
    val scope = rememberCoroutineScope()

    val currentDevice by config.currentDeviceState.collectAsState()

    fun playItem(item: BiliVideoItem) {
        scope.launch {
            try {
                val api = config.getApiService()
                if (currentDevice.isLocal) {
                    val musicMap = api.getMusicList()
                    val allSongs = musicMap.values.flatten().toSet()
                    val matchedSong = allSongs.find { it.contains(item.title) || item.title.contains(it) }

                    if (matchedSong != null) {
                        val info = api.getMusicInfo(matchedSong)
                        val rawUrl = info.url
                        val musicUrl = if (!rawUrl.isNullOrBlank()) {
                            config.getFullMediaUrl(rawUrl)
                        } else {
                            config.getFullMediaUrl("/music/" + URLEncoder.encode(matchedSong, "UTF-8"))
                        }
                        player.playLocal(musicUrl, matchedSong)
                        Toast.makeText(context, "▶️ 正在本机播放: $matchedSong", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "💡 本机播放需先存到本地，请先点击右侧【下载】", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    player.playOnDevice(
                        did = config.selectedDid,
                        deviceName = currentDevice.name,
                        title = item.title,
                        searchKey = item.url
                    )
                    Toast.makeText(context, "🔊 已投送到【${currentDevice.name}】播放！", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "播放请求失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // 顶部搜索框
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = viewModel.keyword,
                onValueChange = { viewModel.keyword = it },
                label = { Text("输入歌名、BV号或完整链接") },
                placeholder = { Text("例如 BV14K4y1A7vD 或 歌曲名称") },
                singleLine = true,
                trailingIcon = {
                    if (viewModel.keyword.isNotEmpty()) {
                        IconButton(onClick = { viewModel.keyword = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空输入框", tint = Color.Gray)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.doSearch(config) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BiliPink),
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
                Spacer(modifier = Modifier.width(4.dp))
                Text("搜索")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (viewModel.isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = BiliPink)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在搜索 Bilibili 音频资源...", color = Color.Gray, fontSize = 13.sp)
                }
            }
        } else if (viewModel.errorMsg != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SearchOff, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(56.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = viewModel.errorMsg ?: "", color = Color.Red, fontSize = 14.sp)
                }
            }
        } else if (!viewModel.hasSearched) {
            // 尚未进行搜索时的空态引导提示
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = BiliPink.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "从 Bilibili 搜索与下载音频",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.DarkGray)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "支持直接输入歌名、BV号（如 BV14K4y1A7vD）或 B站视频链接进行精准解析",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray, textAlign = TextAlign.Center)
                    )
                }
            }
        } else if (viewModel.searchResults.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "未找到相关视频/音频，请换个关键词试试", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            Text(
                text = if (viewModel.isDirectMatch) "🎯 已精准直达解析该视频/音频 (点击即可投送或下载)：" else "找到 ${viewModel.searchResults.size} 条相关视频音频：",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (viewModel.isDirectMatch) Color(0xFF2E7D32) else Color.Gray,
                    fontWeight = if (viewModel.isDirectMatch) FontWeight.Bold else FontWeight.Normal
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(viewModel.searchResults) { item ->
                    val dlState = viewModel.downloadStates[item.bvid]

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 视频封面图
                                AsyncImage(
                                    model = item.pic,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(width = 88.dp, height = 55.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.LightGray.copy(alpha = 0.3f))
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                // 标题与信息
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "👤 ${item.author}",
                                            style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray, fontSize = 11.sp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Text(
                                            text = "⏱️ ${item.duration}",
                                            style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray, fontSize = 11.sp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // 操作按钮：播放 / 投送
                                IconButton(onClick = { playItem(item) }) {
                                    Icon(
                                        imageVector = if (currentDevice.isLocal) Icons.Default.PlayArrow else Icons.Default.Speaker,
                                        contentDescription = "播放",
                                        tint = if (currentDevice.isLocal) TealPrimary else Color(0xFFE65100)
                                    )
                                }

                                // 下载按钮
                                Button(
                                    onClick = { viewModel.startDownload(item, config) },
                                    enabled = dlState == null || dlState.isError,
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (dlState?.isCompleted == true) Color(0xFF2E7D32) else TealPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    if (dlState?.isCompleted == true) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text("已下载", fontSize = 12.sp)
                                    } else if (dlState?.isDownloading == true) {
                                        Text("${dlState.progress}%", fontSize = 12.sp)
                                    } else {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text("下载", fontSize = 12.sp)
                                    }
                                }
                            }

                            // 展开式实时下载进度条
                            if (dlState != null && (dlState.isDownloading || dlState.isCompleted || dlState.isError)) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = dlState.statusText,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 11.sp,
                                                color = if (dlState.isError) Color.Red else if (dlState.isCompleted) Color(0xFF2E7D32) else TealPrimary
                                            )
                                        )
                                        Text(
                                            text = "${dlState.progress}%",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    LinearProgressIndicator(
                                        progress = { dlState.progress / 100f },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = if (dlState.isCompleted) Color(0xFF2E7D32) else if (dlState.isError) Color.Red else TealPrimary,
                                        trackColor = Color.LightGray.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
