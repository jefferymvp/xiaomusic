package com.xiaomusic.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaomusic.android.XiaoMusicApplication
import com.xiaomusic.android.ui.theme.TealPrimary

private fun formatTime(timeMs: Long): String {
    if (timeMs <= 0) return "00:00"
    val totalSeconds = (timeMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun BottomPlayerBar() {
    val playerManager = XiaoMusicApplication.instance.playerManager
    val state by playerManager.playerState.collectAsState()

    // 若当前完全没有歌曲信息且不在播放，则不展示
    if (state.currentSongName.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 顶部微型播放进度指示条
            if (state.duration > 0) {
                val progress = (state.currentPosition.toFloat() / state.duration.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = TealPrimary,
                    trackColor = Color.LightGray.copy(alpha = 0.3f)
                )
            } else if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = TealPrimary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左侧：设备图标 + 歌曲名称与状态/时长信息
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (state.isDevicePlayback) Color(0xFFFFE0B2) else TealPrimary.copy(alpha = 0.12f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.isDevicePlayback) Icons.Default.Speaker else Icons.Default.Smartphone,
                            contentDescription = null,
                            tint = if (state.isDevicePlayback) Color(0xFFE65100) else TealPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.currentSongName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val statusDesc = when {
                                state.isLoading -> "缓冲中..."
                                state.isPlaying -> "正在播放"
                                else -> "已暂停"
                            }
                            val deviceDesc = if (state.isDevicePlayback) state.deviceName else "本机"
                            val timeDesc = if (state.duration > 0) {
                                " · ${formatTime(state.currentPosition)}/${formatTime(state.duration)}"
                            } else ""

                            Text(
                                text = "[$deviceDesc] $statusDesc$timeDesc",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = if (state.isPlaying) TealPrimary else Color.Gray,
                                    fontSize = 11.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 右侧：上一曲、播放/暂停、下一曲 控制按钮组（同时对本机与小爱音箱有效）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = { playerManager.playPrevious() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "上一首",
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { playerManager.togglePlayPause() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(34.dp),
                            tint = TealPrimary
                        )
                    }

                    IconButton(
                        onClick = { playerManager.playNext() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "下一首",
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { playerManager.stop() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.StopCircle,
                            contentDescription = "停止播放",
                            modifier = Modifier.size(26.dp),
                            tint = Color(0xFFE53935)
                        )
                    }
                }
            }
        }
    }
}
