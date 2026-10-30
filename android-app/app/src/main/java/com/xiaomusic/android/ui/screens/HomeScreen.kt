package com.xiaomusic.android.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaomusic.android.XiaoMusicApplication
import com.xiaomusic.android.data.CreatePlaylistRequest
import com.xiaomusic.android.data.PlaylistMusicRequest
import com.xiaomusic.android.data.ServerConfigManager
import com.xiaomusic.android.ui.components.VoiceControlDialog
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

    fun loadMusic(
        config: ServerConfigManager,
        forceRefresh: Boolean = false,
        onSuccess: ((total: Int) -> Unit)? = null,
        onError: ((err: String) -> Unit)? = null
    ) {
        isLoading = true
        errorMsg = null
        viewModelScope.launch {
            try {
                val api = config.getApiService()
                if (forceRefresh) {
                    // 主动调用后端扫描磁盘接口
                    api.refreshMusicList()
                }
                // 获取最新音乐列表
                val map = api.getMusicList(refresh = forceRefresh)
                musicMap = map
                if (!map.containsKey(selectedPlaylist) && map.isNotEmpty()) {
                    selectedPlaylist = map.keys.first()
                }
                hasLoaded = true
                val total = map.values.flatten().toSet().size
                onSuccess?.invoke(total)
            } catch (e: Exception) {
                val msg = "加载歌曲失败: ${e.localizedMessage}"
                errorMsg = msg
                onError?.invoke(msg)
            } finally {
                isLoading = false
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onNavigateToDownload: ((keyword: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val config = XiaoMusicApplication.instance.serverConfig
    val player = XiaoMusicApplication.instance.playerManager
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val currentDevice by config.currentDeviceState.collectAsState()

    // 搜索关键字
    var searchQuery by remember { mutableStateOf("") }

    // 长按歌曲指定歌单的弹窗目标歌曲
    var songForPlaylistDialog by remember { mutableStateOf<String?>(null) }

    // 语音控制弹窗状态
    var showVoiceDialog by remember { mutableStateOf(false) }

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

    // 触发重新扫描磁盘并刷新
    fun triggerDiskRescan() {
        Toast.makeText(context, "⏳ 正在通知服务端重新扫描磁盘曲库...", Toast.LENGTH_SHORT).show()
        viewModel.loadMusic(
            config = config,
            forceRefresh = true,
            onSuccess = { total ->
                Toast.makeText(context, "✅ 磁盘扫描完成，当前曲库共 $total 首歌曲", Toast.LENGTH_SHORT).show()
            },
            onError = { err ->
                Toast.makeText(context, "❌ 刷新失败: $err", Toast.LENGTH_LONG).show()
            }
        )
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
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "播放异常: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 下载歌曲到手机本地
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
                    "📥 已开始下载至手机【下载】目录：\n$fileName",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, "下载请求失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val currentSongs = viewModel.musicMap[viewModel.selectedPlaylist] ?: emptyList()

    // 搜索实时过滤
    val displaySongs = remember(currentSongs, searchQuery) {
        if (searchQuery.isBlank()) {
            currentSongs
        } else {
            val q = searchQuery.trim().lowercase()
            currentSongs.filter { it.lowercase().contains(q) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题与当前输出设备胶囊
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
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
                        text = "共收录 ${viewModel.musicMap.values.flatten().toSet().size} 首歌曲 · 长按歌曲可分配歌单",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
                    )
                }

                IconButton(onClick = { triggerDiskRescan() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "重新扫描磁盘曲库",
                        tint = TealPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

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

            Spacer(modifier = Modifier.height(8.dp))

            // 🔍 本地歌曲搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                placeholder = {
                    Text(
                        text = "搜索当前歌单歌曲 (支持模糊匹配)...",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = TealPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清空搜索",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(onClick = { showVoiceDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "语音点歌/下载",
                                tint = TealPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TealPrimary,
                    unfocusedBorderColor = Color.LightGray.copy(alpha = 0.6f)
                )
            )
        }

        // 歌单分类 Tab 标签栏
        if (viewModel.musicMap.isNotEmpty()) {
            ScrollableTabRow(
                selectedTabIndex = viewModel.musicMap.keys.toList().indexOf(viewModel.selectedPlaylist).coerceAtLeast(0),
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                viewModel.musicMap.keys.forEach { listName ->
                    Tab(
                        selected = viewModel.selectedPlaylist == listName,
                        onClick = {
                            viewModel.selectedPlaylist = listName
                            searchQuery = "" // 切换歌单时自动重置搜索框
                        },
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
                    Button(onClick = { triggerDiskRescan() }) {
                        Text("重试并重新扫描磁盘")
                    }
                }
            }
        } else if (displaySongs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (searchQuery.isNotBlank()) {
                    Text(text = "未找到包含「$searchQuery」的歌曲", color = Color.Gray, fontSize = 14.sp)
                } else {
                    Text(text = "当前歌单暂无歌曲，快去「B站搜索」下载吧~", color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displaySongs, key = { it }) { songName ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { playSong(songName) },
                                onLongClick = { songForPlaylistDialog = songName }
                            ),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 歌曲图标
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = TealPrimary,
                                modifier = Modifier.size(24.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            // 歌曲名（点击整行短按直接播放）
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = songName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "轻触播放 · 长按加入歌单",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color.Gray.copy(alpha = 0.7f),
                                        fontSize = 11.sp
                                    )
                                )
                            }

                            // 更多操作：分配歌单快捷入口
                            IconButton(
                                onClick = { songForPlaylistDialog = songName },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlaylistAdd,
                                    contentDescription = "指定歌单",
                                    tint = TealPrimary.copy(alpha = 0.8f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // 下载到手机公共下载目录
                            IconButton(
                                onClick = { downloadSongToPhone(songName) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "下载到手机",
                                    tint = TealPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        // 🎤 悬浮语音点歌按钮 (右下角常驻入口)
        FloatingActionButton(
            onClick = { showVoiceDialog = true },
            containerColor = TealPrimary,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "语音点歌",
                modifier = Modifier.size(26.dp)
            )
        }
    }

    // 🎤 离线语音控制对话框
    if (showVoiceDialog) {
        val allSongs = remember(viewModel.musicMap) {
            viewModel.musicMap.values.flatten().distinct()
        }
        VoiceControlDialog(
            allSongs = allSongs,
            onDismiss = { showVoiceDialog = false },
            onPlaySong = { matchedSong ->
                playSong(matchedSong)
                showVoiceDialog = false
            },
            onNavigateToDownload = { keyword ->
                showVoiceDialog = false
                onNavigateToDownload?.invoke(keyword)
            }
        )
    }

    // 🎵 歌单指定对话框 (长按歌曲触发)
    songForPlaylistDialog?.let { targetSong ->
        AssignPlaylistDialog(
            songName = targetSong,
            musicMap = viewModel.musicMap,
            onDismiss = { songForPlaylistDialog = null },
            onPlaylistUpdated = {
                viewModel.loadMusic(config, forceRefresh = false)
            }
        )
    }
}

/**
 * 指定归属歌单弹窗
 */
@Composable
fun AssignPlaylistDialog(
    songName: String,
    musicMap: Map<String, List<String>>,
    onDismiss: () -> Unit,
    onPlaylistUpdated: () -> Unit
) {
    val context = LocalContext.current
    val config = XiaoMusicApplication.instance.serverConfig
    val scope = rememberCoroutineScope()

    // 排除“全部”，仅保留可编辑的自定义歌单
    val customPlaylists = remember(musicMap) {
        musicMap.keys.filter { it != "全部" }
    }

    var newPlaylistName by remember { mutableStateOf("") }
    var isOperating by remember { mutableStateOf(false) }

    // 将歌曲添加到指定歌单
    fun addSongTo(playlist: String) {
        isOperating = true
        scope.launch {
            try {
                val api = config.getApiService()
                val res = api.addMusicToPlaylist(
                    PlaylistMusicRequest(name = playlist, musicList = listOf(songName))
                )
                if (res.ret == "OK") {
                    Toast.makeText(context, "✅ 已将歌曲加入歌单【$playlist】", Toast.LENGTH_SHORT).show()
                    onPlaylistUpdated()
                    onDismiss()
                } else {
                    Toast.makeText(context, "操作反馈: ${res.ret ?: "未知结果"}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "加入失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                isOperating = false
            }
        }
    }

    // 从歌单中移除歌曲
    fun removeSongFrom(playlist: String) {
        isOperating = true
        scope.launch {
            try {
                val api = config.getApiService()
                val res = api.removeMusicFromPlaylist(
                    PlaylistMusicRequest(name = playlist, musicList = listOf(songName))
                )
                if (res.ret == "OK") {
                    Toast.makeText(context, "已从歌单【$playlist】中移除", Toast.LENGTH_SHORT).show()
                    onPlaylistUpdated()
                    onDismiss()
                } else {
                    Toast.makeText(context, "移除反馈: ${res.ret ?: "未知结果"}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "移除失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                isOperating = false
            }
        }
    }

    // 创建新歌单并直接加入该歌曲
    fun createAndAdd(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(context, "请输入新歌单名称", Toast.LENGTH_SHORT).show()
            return
        }
        isOperating = true
        scope.launch {
            try {
                val api = config.getApiService()
                val createRes = api.createPlaylist(CreatePlaylistRequest(name = trimmed))
                if (createRes.ret == "OK") {
                    // 创建成功，紧接着将该歌曲加入新歌单
                    api.addMusicToPlaylist(
                        PlaylistMusicRequest(name = trimmed, musicList = listOf(songName))
                    )
                    Toast.makeText(context, "🎉 已创建歌单【$trimmed】并成功收纳该歌曲", Toast.LENGTH_SHORT).show()
                    onPlaylistUpdated()
                    onDismiss()
                } else {
                    Toast.makeText(context, "创建歌单失败: ${createRes.ret ?: "已存在或异常"}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "操作失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                isOperating = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "指定归属歌单", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = songName,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
            ) {
                if (isOperating) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TealPrimary, modifier = Modifier.size(32.dp))
                    }
                } else {
                    Text(
                        text = "选择要加入的已有歌单：",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (customPlaylists.isEmpty()) {
                        Text(
                            text = "暂无自定义歌单，可在下方新建一个~",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth()
                        ) {
                            items(customPlaylists) { pName ->
                                val songsInThisPlaylist = musicMap[pName] ?: emptyList()
                                val isAlreadyIn = songsInThisPlaylist.contains(songName)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isAlreadyIn) TealPrimary.copy(alpha = 0.1f) else Color.Transparent
                                        )
                                        .clickable {
                                            if (isAlreadyIn) {
                                                removeSongFrom(pName)
                                            } else {
                                                addSongTo(pName)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (isAlreadyIn) TealPrimary else Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "$pName (${songsInThisPlaylist.size}首)",
                                            fontSize = 14.sp,
                                            fontWeight = if (isAlreadyIn) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isAlreadyIn) TealPrimary else Color.Black
                                        )
                                    }

                                    if (isAlreadyIn) {
                                        Text(
                                            text = "已在歌单 (点击移出)",
                                            fontSize = 11.sp,
                                            color = Color(0xFFD32F2F)
                                        )
                                    } else {
                                        Text(
                                            text = "+ 加入",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TealPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = Color.LightGray.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(8.dp))

                    // 新建歌单快捷输入栏
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            placeholder = { Text("新歌单名...", fontSize = 12.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { createAndAdd(newPlaylistName) },
                            enabled = newPlaylistName.isNotBlank() && !isOperating,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("新建并加入", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = TealPrimary)
            }
        }
    )
}
