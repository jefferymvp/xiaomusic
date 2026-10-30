package com.xiaomusic.android.data

import com.google.gson.annotations.SerializedName

// B站搜索响应数据模型
data class BiliSearchResponse(
    val success: Boolean,
    val total: Int = 0,
    val error: String? = null,
    val data: List<BiliVideoItem> = emptyList(),
    @SerializedName("is_direct") val isDirect: Boolean = false
)

data class BiliVideoItem(
    val bvid: String,
    val title: String,
    val author: String,
    val duration: String,
    val play: Any? = null,
    val pic: String? = null,
    val url: String,
    @SerializedName("is_direct") val isDirect: Boolean = false
)

// 歌曲信息
data class MusicInfoResponse(
    val ret: String,
    val name: String,
    val url: String? = null
)

// 单曲下载请求与响应
data class DownloadMusicRequest(
    val url: String,
    val name: String
)

data class DownloadResponse(
    val ret: String,
    @SerializedName("task_id") val taskId: String? = null
)

// 下载进度
data class DownloadProgressResponse(
    val ret: String,
    @SerializedName("task_id") val taskId: String,
    val total: Int = 1,
    val completed: Int = 0,
    val progress: Int = 0,
    val status: String,
    @SerializedName("current_song") val currentSong: String? = null
)

// 服务端设置与版本检查
data class VersionResponse(
    val version: String? = null
)

// 小爱音箱与服务端配置
data class SettingResponse(
    @SerializedName("mi_did") val miDid: String? = null,
    val devices: Map<String, DeviceMeta>? = null,
    val hostname: String? = null,
    val port: Any? = null
)

data class DeviceMeta(
    val name: String? = null,
    val hardware: String? = null,
    val model: String? = null
)

// 音箱投送播放请求
data class PlayMusicRequest(
    val did: String,
    val musicname: String,
    val searchkey: String = ""
)

// 音箱口令控制请求
data class DidCmdRequest(
    val did: String,
    val cmd: String
)

// 设备基础请求 (如停止关机)
data class DidRequest(
    val did: String
)

// 音箱实时播放状态响应
data class PlayingMusicResponse(
    val ret: String? = null,
    @SerializedName("is_playing") val isPlaying: Boolean = false,
    @SerializedName("cur_music") val curMusic: String? = null,
    @SerializedName("cur_playlist") val curPlaylist: String? = null,
    val offset: Double = 0.0,
    val duration: Double = 0.0
)

// 服务端连接状态模型
data class ConnectionState(
    val isTesting: Boolean = false,
    val isSuccess: Boolean? = null,
    val message: String? = null,
    val serverVersion: String? = null
)

data class SimpleRetResponse(
    val ret: String? = null
)

// 本地统一展示的播放输出设备
data class PlaybackDevice(
    val did: String,
    val name: String,
    val isLocal: Boolean = false
)

// 歌单管理数据模型
data class CreatePlaylistRequest(
    val name: String
)

data class PlaylistMusicRequest(
    val name: String,
    @SerializedName("music_list") val musicList: List<String>
)

data class PlaylistNamesResponse(
    val ret: String? = null,
    val names: List<String> = emptyList()
)
