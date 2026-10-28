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

data class SimpleRetResponse(
    val ret: String? = null
)

// 本地统一展示的播放输出设备
data class PlaybackDevice(
    val did: String,
    val name: String,
    val isLocal: Boolean = false
)
