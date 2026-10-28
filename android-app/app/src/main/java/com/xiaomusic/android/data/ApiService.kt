package com.xiaomusic.android.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {

    // 1. 检查后端健康状态与版本
    @GET("/getversion")
    suspend fun getVersion(): VersionResponse

    // 2. 获取本地歌曲列表 (返回歌单名 -> 歌曲列表 map)
    @GET("/musiclist")
    suspend fun getMusicList(
        @Query("refresh") refresh: Boolean = false
    ): Map<String, List<String>>

    // 3. 重新扫描本地磁盘并刷新曲库
    @GET("/refreshmusiclist")
    suspend fun refreshMusicList(): SimpleRetResponse

    // 4. 获取单曲流媒体音频地址
    @GET("/musicinfo")
    suspend fun getMusicInfo(
        @Query("name") name: String
    ): MusicInfoResponse

    // 5. B站视频/音乐搜索与直达
    @GET("/api/bili/search")
    suspend fun searchBilibili(
        @Query("keyword") keyword: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): BiliSearchResponse

    // 6. 触发下载
    @POST("/downloadonemusic")
    suspend fun downloadMusic(
        @Body request: DownloadMusicRequest
    ): DownloadResponse

    // 7. 轮询下载任务进度
    @GET("/download_progress")
    suspend fun getDownloadProgress(
        @Query("task_id") taskId: String
    ): DownloadProgressResponse

    // 8. 获取服务端配置与小爱音箱设备列表
    @GET("/getsetting")
    suspend fun getSetting(): SettingResponse

    // 9. 投送到小爱音箱设备端播放
    @POST("/playmusic")
    suspend fun playMusic(
        @Body request: PlayMusicRequest
    ): SimpleRetResponse
}
