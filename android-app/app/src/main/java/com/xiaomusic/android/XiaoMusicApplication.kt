package com.xiaomusic.android

import android.app.Application
import com.xiaomusic.android.data.ServerConfigManager
import com.xiaomusic.android.player.MusicPlayerManager

class XiaoMusicApplication : Application() {

    companion object {
        lateinit var instance: XiaoMusicApplication
            private set
    }

    lateinit var serverConfig: ServerConfigManager
        private set

    lateinit var playerManager: MusicPlayerManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        serverConfig = ServerConfigManager(this)
        playerManager = MusicPlayerManager(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        playerManager.release()
    }
}

