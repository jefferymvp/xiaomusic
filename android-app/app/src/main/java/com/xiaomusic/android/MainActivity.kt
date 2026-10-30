package com.xiaomusic.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaomusic.android.ui.components.BottomPlayerBar
import com.xiaomusic.android.ui.screens.BiliSearchScreen
import com.xiaomusic.android.ui.screens.BiliSearchViewModel
import com.xiaomusic.android.ui.screens.HomeScreen
import com.xiaomusic.android.ui.screens.SettingsScreen
import com.xiaomusic.android.ui.theme.XiaoMusicTheme

enum class Screen(val title: String, val icon: ImageVector) {
    HOME("本地曲库", Icons.Default.LibraryMusic),
    BILI_SEARCH("B站搜索", Icons.Default.VideoLibrary),
    SETTINGS("服务设置", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaoMusicTheme {
                var currentScreen by remember { mutableStateOf(Screen.HOME) }
                val biliSearchViewModel: BiliSearchViewModel = viewModel()

                Scaffold(
                    bottomBar = {
                        Column {
                            // 常驻底部播放控制栏（处于底部导航栏上方，自动计入 Scaffold innerPadding）
                            BottomPlayerBar()

                            NavigationBar {
                                Screen.values().forEach { screen ->
                                    NavigationBarItem(
                                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                                        label = { Text(screen.title) },
                                        selected = currentScreen == screen,
                                        onClick = { currentScreen = screen }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentScreen) {
                            Screen.HOME -> HomeScreen(
                                onNavigateToDownload = { keyword ->
                                    val config = XiaoMusicApplication.instance.serverConfig
                                    biliSearchViewModel.keyword = keyword
                                    biliSearchViewModel.doSearch(config)
                                    currentScreen = Screen.BILI_SEARCH
                                }
                            )
                            Screen.BILI_SEARCH -> BiliSearchScreen(viewModel = biliSearchViewModel)
                            Screen.SETTINGS -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}

