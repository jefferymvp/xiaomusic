package com.xiaomusic.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ServerConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val KEY_HOST = "key_host"
        private const val KEY_PORT = "key_port"
        private const val KEY_DID = "key_selected_did"
        private const val KEY_DID_NAME = "key_selected_did_name"

        const val DID_LOCAL = "web_device"
        const val DEFAULT_LOCAL_NAME = "本机 (当前手机)"
        private const val DEFAULT_HOST = "192.168.1.175"
        private const val OLD_DEFAULT_HOST_1 = "192.168.6.114"
        private const val OLD_DEFAULT_HOST_2 = "192.168.6.58"
        private const val DEFAULT_PORT = 58090
    }

    var host: String
        get() {
            val saved = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
            if (saved == OLD_DEFAULT_HOST_1 || saved == OLD_DEFAULT_HOST_2) {
                // 自动平滑升级为当前宿主机实际 IP 192.168.1.175
                prefs.edit().putString(KEY_HOST, DEFAULT_HOST).apply()
                return DEFAULT_HOST
            }
            return saved
        }
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

    // 全局连接状态 (持久存在于内存单例，切换页面不重置、不闪烁)
    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 全局音箱设备列表
    private val _deviceList = MutableStateFlow<List<PlaybackDevice>>(
        listOf(PlaybackDevice(DID_LOCAL, DEFAULT_LOCAL_NAME, isLocal = true))
    )
    val deviceList: StateFlow<List<PlaybackDevice>> = _deviceList.asStateFlow()

    private val _isLoadingDevices = MutableStateFlow(false)
    val isLoadingDevices: StateFlow<Boolean> = _isLoadingDevices.asStateFlow()

    init {
        // App 启动时后台静默检测连接并同步设备列表
        testConnectionAsync(isSilent = true)
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

    @Synchronized
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
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
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
     * 测试指定或当前配置的连通性
     */
    suspend fun testConnection(targetHost: String = host, targetPort: Int = port): Boolean {
        host = targetHost.trim()
        port = targetPort
        _connectionState.value = _connectionState.value.copy(
            isTesting = true,
            message = "正在连接 $baseUrl ..."
        )

        return try {
            val api = getApiService()
            val versionRes = api.getVersion()
            val ver = versionRes.version ?: "0.6.1"
            _connectionState.value = ConnectionState(
                isTesting = false,
                isSuccess = true,
                message = "✅ 连接成功！服务端 XiaoMusic 版本: v$ver",
                serverVersion = ver
            )
            loadDevices()
            true
        } catch (e: Exception) {
            _connectionState.value = ConnectionState(
                isTesting = false,
                isSuccess = false,
                message = "❌ 连接失败: ${e.localizedMessage ?: "网络超时或地址不正确"}"
            )
            false
        }
    }

    fun testConnectionAsync(targetHost: String = host, targetPort: Int = port, isSilent: Boolean = false) {
        scope.launch {
            if (!isSilent) {
                testConnection(targetHost, targetPort)
            } else {
                // 静默初次测试：只在成功时更新状态，失败不打扰，也不置为错误闪烁
                try {
                    val api = getApiService()
                    val versionRes = api.getVersion()
                    val ver = versionRes.version ?: "0.6.1"
                    _connectionState.value = ConnectionState(
                        isTesting = false,
                        isSuccess = true,
                        message = "✅ 已连接到服务端 (v$ver)",
                        serverVersion = ver
                    )
                    loadDevices()
                } catch (e: Exception) {
                    // 静默检测失败时不覆盖用户界面，保持初始待测或上次状态
                }
            }
        }
    }

    /**
     * 刷新设备列表
     */
    suspend fun loadDevices(): List<PlaybackDevice> {
        _isLoadingDevices.value = true
        return try {
            val devices = fetchDevicesFromServer()
            _deviceList.value = devices
            devices
        } finally {
            _isLoadingDevices.value = false
        }
    }

    fun loadDevicesAsync() {
        scope.launch {
            loadDevices()
        }
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
            // 网络异常时保持本机与已有设备
        }
        return list
    }
}
