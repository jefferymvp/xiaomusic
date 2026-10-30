package com.xiaomusic.android.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.xiaomusic.android.XiaoMusicApplication
import com.xiaomusic.android.data.DidCmdRequest
import com.xiaomusic.android.data.DidRequest
import com.xiaomusic.android.data.PlayMusicRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentSongName: String = "",
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val isLoading: Boolean = false,
    val isDevicePlayback: Boolean = false, // 是否是投送到小爱音箱
    val deviceName: String = "",           // 投送音箱名称
    val deviceDid: String = "",            // 音箱 DID
    val error: String? = null
)

class MusicPlayerManager(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var devicePollingJob: Job? = null

    // 本地播放列表与当前歌曲索引
    private var currentPlaylist: List<String> = emptyList()
    private var currentIndex: Int = -1

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!_playerState.value.isDevicePlayback) {
                    _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                    if (isPlaying) {
                        startProgressTracker()
                    } else {
                        progressJob?.cancel()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!_playerState.value.isDevicePlayback) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            _playerState.value = _playerState.value.copy(isLoading = true)
                        }
                        Player.STATE_READY -> {
                            _playerState.value = _playerState.value.copy(
                                isLoading = false,
                                duration = player.duration.coerceAtLeast(0L)
                            )
                        }
                        Player.STATE_ENDED -> {
                            _playerState.value = _playerState.value.copy(isPlaying = false, currentPosition = 0L)
                            progressJob?.cancel()
                            // 本地自动播放下一曲
                            playNext()
                        }
                        Player.STATE_IDLE -> {
                            _playerState.value = _playerState.value.copy(isLoading = false)
                        }
                    }
                }
            }
        })
    }

    /**
     * 本机播放歌曲
     */
    fun playLocal(url: String, title: String, playlist: List<String> = emptyList()) {
        devicePollingJob?.cancel()

        if (playlist.isNotEmpty()) {
            currentPlaylist = playlist
            currentIndex = playlist.indexOf(title).coerceAtLeast(0)
        } else if (!currentPlaylist.contains(title)) {
            currentPlaylist = listOf(title)
            currentIndex = 0
        } else {
            currentIndex = currentPlaylist.indexOf(title)
        }

        _playerState.value = _playerState.value.copy(
            currentSongName = title,
            isLoading = true,
            isPlaying = true,
            isDevicePlayback = false,
            deviceName = "手机本机",
            deviceDid = "",
            error = null
        )

        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    /**
     * 投送到小爱音箱播放
     */
    fun playOnDevice(
        did: String,
        deviceName: String,
        title: String,
        searchKey: String = "",
        playlist: List<String> = emptyList()
    ) {
        // 如果本地正在播放，暂停本地发声
        if (player.isPlaying) {
            player.pause()
        }
        progressJob?.cancel()

        if (playlist.isNotEmpty()) {
            currentPlaylist = playlist
            currentIndex = playlist.indexOf(title).coerceAtLeast(0)
        }

        _playerState.value = _playerState.value.copy(
            currentSongName = title,
            isLoading = true,
            isPlaying = true,
            isDevicePlayback = true,
            deviceName = deviceName,
            deviceDid = did,
            currentPosition = 0L,
            duration = 0L,
            error = null
        )

        // 发送投送请求给后端，并启动音箱播放状态轮询
        scope.launch {
            try {
                val api = XiaoMusicApplication.instance.serverConfig.getApiService()
                api.playMusic(PlayMusicRequest(did = did, musicname = title, searchkey = searchKey))
            } catch (e: Exception) {
                _playerState.value = _playerState.value.copy(
                    isLoading = false,
                    error = "投送失败: ${e.localizedMessage}"
                )
            }
            startDevicePolling(did, deviceName)
        }
    }

    /**
     * 轮询拉取小爱音箱的实时播放状态与进度
     */
    fun startDevicePolling(did: String, deviceName: String) {
        devicePollingJob?.cancel()
        devicePollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val api = XiaoMusicApplication.instance.serverConfig.getApiService()
                    val res = api.getPlayingMusic(did)
                    withContext(Dispatchers.Main) {
                        if (_playerState.value.isDevicePlayback && _playerState.value.deviceDid == did) {
                            val songName = if (!res.curMusic.isNullOrBlank()) res.curMusic else _playerState.value.currentSongName
                            val posMs = (res.offset * 1000).toLong().coerceAtLeast(0L)
                            val durMs = (res.duration * 1000).toLong().coerceAtLeast(0L)

                            _playerState.value = _playerState.value.copy(
                                currentSongName = songName,
                                isPlaying = res.isPlaying,
                                currentPosition = posMs,
                                duration = durMs,
                                isLoading = false,
                                deviceName = deviceName
                            )
                        }
                    }
                } catch (e: Exception) {
                    // 网络抖动忽略
                }
                delay(1500)
            }
        }
    }

    /**
     * 播放 / 暂停 切换控制（同时支持本机和小爱音箱）
     */
    fun togglePlayPause() {
        val state = _playerState.value
        if (!state.isDevicePlayback) {
            // 本机播放切换
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        } else {
            // 小爱音箱播放切换
            val did = state.deviceDid
            if (did.isBlank()) return

            scope.launch {
                try {
                    val api = XiaoMusicApplication.instance.serverConfig.getApiService()
                    if (state.isPlaying) {
                        // 正在播放 -> 暂停/停止音箱
                        _playerState.value = state.copy(isPlaying = false)
                        api.stopDevice(DidRequest(did = did))
                    } else {
                        // 已暂停 -> 恢复播放 / 播放当前歌曲
                        _playerState.value = state.copy(isPlaying = true, isLoading = true)
                        if (state.currentSongName.isNotEmpty()) {
                            api.playMusic(PlayMusicRequest(did = did, musicname = state.currentSongName))
                        } else {
                            api.sendCmd(DidCmdRequest(did = did, cmd = "播放歌曲"))
                        }
                    }
                    delay(500)
                    pollOnceDeviceStatus(did)
                } catch (e: Exception) {
                    // 异常恢复
                }
            }
        }
    }

    /**
     * 下一曲控制（同时支持本机和小爱音箱）
     */
    fun playNext() {
        val state = _playerState.value
        if (!state.isDevicePlayback) {
            // 本地下一首
            if (currentPlaylist.isNotEmpty()) {
                currentIndex = (currentIndex + 1) % currentPlaylist.size
                val nextSong = currentPlaylist[currentIndex]
                playLocalSongByName(nextSong)
            }
        } else {
            // 小爱音箱下一首
            val did = state.deviceDid
            if (did.isBlank()) return

            scope.launch {
                try {
                    _playerState.value = state.copy(isLoading = true)
                    val api = XiaoMusicApplication.instance.serverConfig.getApiService()
                    api.sendCmd(DidCmdRequest(did = did, cmd = "下一首"))
                    delay(800)
                    pollOnceDeviceStatus(did)
                } catch (e: Exception) {
                    // 异常处理
                }
            }
        }
    }

    /**
     * 上一曲控制（同时支持本机和小爱音箱）
     */
    fun playPrevious() {
        val state = _playerState.value
        if (!state.isDevicePlayback) {
            // 本地上一首
            if (currentPlaylist.isNotEmpty()) {
                currentIndex = if (currentIndex - 1 < 0) currentPlaylist.size - 1 else currentIndex - 1
                val prevSong = currentPlaylist[currentIndex]
                playLocalSongByName(prevSong)
            }
        } else {
            // 小爱音箱上一首
            val did = state.deviceDid
            if (did.isBlank()) return

            scope.launch {
                try {
                    _playerState.value = state.copy(isLoading = true)
                    val api = XiaoMusicApplication.instance.serverConfig.getApiService()
                    api.sendCmd(DidCmdRequest(did = did, cmd = "上一首"))
                    delay(800)
                    pollOnceDeviceStatus(did)
                } catch (e: Exception) {
                    // 异常处理
                }
            }
        }
    }

    private fun playLocalSongByName(name: String) {
        scope.launch {
            try {
                val config = XiaoMusicApplication.instance.serverConfig
                val api = config.getApiService()
                val info = api.getMusicInfo(name)
                val rawUrl = info.url
                val fullUrl = if (!rawUrl.isNullOrBlank()) {
                    config.getFullMediaUrl(rawUrl)
                } else {
                    config.getFullMediaUrl("/music/" + URLEncoder.encode(name, "UTF-8"))
                }
                playLocal(fullUrl, name, currentPlaylist)
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    private suspend fun pollOnceDeviceStatus(did: String) {
        try {
            val api = XiaoMusicApplication.instance.serverConfig.getApiService()
            val res = api.getPlayingMusic(did)
            if (_playerState.value.isDevicePlayback && _playerState.value.deviceDid == did) {
                val songName = if (!res.curMusic.isNullOrBlank()) res.curMusic else _playerState.value.currentSongName
                val posMs = (res.offset * 1000).toLong().coerceAtLeast(0L)
                val durMs = (res.duration * 1000).toLong().coerceAtLeast(0L)

                _playerState.value = _playerState.value.copy(
                    currentSongName = songName,
                    isPlaying = res.isPlaying,
                    currentPosition = posMs,
                    duration = durMs,
                    isLoading = false
                )
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun seekTo(positionMs: Long) {
        if (!_playerState.value.isDevicePlayback) {
            player.seekTo(positionMs)
        }
    }

    fun stop() {
        progressJob?.cancel()
        devicePollingJob?.cancel()
        if (!_playerState.value.isDevicePlayback) {
            player.stop()
            player.clearMediaItems()
        } else {
            val did = _playerState.value.deviceDid
            if (did.isNotBlank()) {
                scope.launch {
                    try {
                        val api = XiaoMusicApplication.instance.serverConfig.getApiService()
                        api.stopDevice(DidRequest(did = did))
                    } catch (e: Exception) {
                        // 忽略
                    }
                }
            }
        }
        _playerState.value = _playerState.value.copy(
            isPlaying = false,
            currentPosition = 0L,
            currentSongName = "",
            isLoading = false
        )
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (player.isPlaying && !_playerState.value.isDevicePlayback) {
                    _playerState.value = _playerState.value.copy(
                        currentPosition = player.currentPosition,
                        duration = player.duration.coerceAtLeast(0L)
                    )
                }
                delay(500)
            }
        }
    }

    fun release() {
        progressJob?.cancel()
        devicePollingJob?.cancel()
        player.release()
    }
}
