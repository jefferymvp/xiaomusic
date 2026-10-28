package com.xiaomusic.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ServerConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HOST = "key_host"
        private const val KEY_PORT = "key_port"
        private const val KEY_DID = "key_selected_did"
        private const val KEY_DID_NAME = "key_selected_did_name"

        const val DID_LOCAL = "web_device"
        const val DEFAULT_LOCAL_NAME = "本机 (当前手机)"
        private const val DEFAULT_HOST = "192.168.6.58"
        private const val DEFAULT_PORT = 58090
    }

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var selectedDid: String
        get() = prefs.getString(KEY_DID, DID_LOCAL) ?: DID_LOCAL
        set(value) {
            prefs.edit().putString(KEY_DID, value).apply()
            _currentDeviceState.value = PlaybackDevice(value, selectedDeviceName, isLocalPlayback())
        }

    var selectedDeviceName: String
        get() = prefs.getString(KEY_DID_NAME, DEFAULT_LOCAL_NAME) ?: DEFAULT_LOCAL_NAME
        set(value) {
            prefs.edit().putString(KEY_DID_NAME, value).apply()
            _currentDeviceState.value = PlaybackDevice(selectedDid, value, isLocalPlayback())
        }

    fun isLocalPlayback(): Boolean = (selectedDid == DID_LOCAL)

    private val _currentDeviceState = MutableStateFlow(
        PlaybackDevice(selectedDid, selectedDeviceName, isLocalPlayback())
    )
    val currentDeviceState: StateFlow<PlaybackDevice> = _currentDeviceState.asStateFlow()

    fun selectDevice(device: PlaybackDevice) {
        selectedDeviceName = device.name
        selectedDid = device.did
    }

    val baseUrl: String
        get() {
            val cleanHost = host.removePrefix("http://").removePrefix("https://").trimEnd('/')
            return "http://$cleanHost:$port/"
        }

    fun getFullMediaUrl(relativePathOrUrl: String): String {
        if (relativePathOrUrl.startsWith("http://") || relativePathOrUrl.startsWith("https://")) {
            try {
                val uri = java.net.URI(relativePathOrUrl)
                val pathAndQuery = uri.rawPath + if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
                return baseUrl.trimEnd('/') + pathAndQuery
            } catch (e: Exception) {
                return relativePathOrUrl
            }
        }
        val path = if (relativePathOrUrl.startsWith("/")) relativePathOrUrl else "/$relativePathOrUrl"
        return baseUrl.trimEnd('/') + path
    }

    private var cachedApiService: ApiService? = null
    private var lastBaseUrl: String? = null

    fun getApiService(): ApiService {
        val currentBaseUrl = baseUrl
        if (cachedApiService != null && lastBaseUrl == currentBaseUrl) {
            return cachedApiService!!
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(currentBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        lastBaseUrl = currentBaseUrl
        cachedApiService = retrofit.create(ApiService::class.java)
        return cachedApiService!!
    }

    /**
     * 从服务端 /getsetting 同步音箱设备列表，并合并本机选项
     */
    suspend fun fetchDevicesFromServer(): List<PlaybackDevice> {
        val list = mutableListOf<PlaybackDevice>()
        // 默认第一项是手机本机
        list.add(PlaybackDevice(DID_LOCAL, DEFAULT_LOCAL_NAME, isLocal = true))

        try {
            val setting = getApiService().getSetting()
            val didStr = setting.miDid
            if (!didStr.isNullOrBlank()) {
                val didArray = didStr.split(",")
                for (d in didArray) {
                    val trimmedDid = d.trim()
                    if (trimmedDid.isNotEmpty()) {
                        val metaName = setting.devices?.get(trimmedDid)?.name
                        val displayName = if (!metaName.isNullOrBlank()) {
                            "🔊 $metaName ($trimmedDid)"
                        } else {
                            "🔊 小爱音箱 ($trimmedDid)"
                        }
                        list.add(PlaybackDevice(trimmedDid, displayName, isLocal = false))
                    }
                }
            }
        } catch (e: Exception) {
            // 网络异常时保持本机
        }
        return list
    }
}
