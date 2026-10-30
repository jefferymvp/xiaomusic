package com.xiaomusic.android.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.xiaomusic.android.XiaoMusicApplication
import com.xiaomusic.android.ui.theme.BiliPink
import com.xiaomusic.android.ui.theme.TealPrimary
import com.xiaomusic.android.voice.VoiceCommandResult
import com.xiaomusic.android.voice.VoiceIntentParser
import com.xiaomusic.android.voice.VoiceIntentType
import com.xiaomusic.android.voice.VoiceRecordState
import kotlinx.coroutines.delay

/**
 * 语音控制对话框 (支持离线识别“我想听xxx”点歌与“我要下载xxx”跳转下载)
 */
@Composable
fun VoiceControlDialog(
    allSongs: List<String>,
    onDismiss: () -> Unit,
    onPlaySong: (String) -> Unit,
    onNavigateToDownload: (keyword: String) -> Unit
) {
    val context = LocalContext.current
    val voiceManager = XiaoMusicApplication.instance.voiceManager

    val recordState by voiceManager.state.collectAsState()
    val partialText by voiceManager.partialText.collectAsState()
    val audioVolume by voiceManager.audioVolume.collectAsState()
    val errorMsg by voiceManager.errorMessage.collectAsState()

    var commandResult by remember { mutableStateOf<VoiceCommandResult?>(null) }
    var matchedLocalSong by remember { mutableStateOf<String?>(null) }
    var pinyinResult by remember { mutableStateOf<com.xiaomusic.android.voice.PinyinMatchResult?>(null) }
    var hasHandledAction by remember { mutableStateOf(false) }

    // 处理最终识别文本
    fun handleFinalText(text: String) {
        if (text.isBlank()) {
            Toast.makeText(context, "未听清您说的话，请重试~", Toast.LENGTH_SHORT).show()
            return
        }

        val result = VoiceIntentParser.parse(text)
        commandResult = result

        when (result.type) {
            VoiceIntentType.PLAY -> {
                // 🎶 使用智能拼音比对 (优先本地曲库，未命中再检索 72,523 全量华语歌名大库)
                val match = com.xiaomusic.android.voice.PinyinMatcher.matchAndCorrect(result.keyword, allSongs, context)
                pinyinResult = match

                if (match != null && match.isLocalAvailable) {
                    matchedLocalSong = match.matchedSong
                    hasHandledAction = true
                    onPlaySong(match.matchedSong)
                } else {
                    matchedLocalSong = null
                }
            }

            VoiceIntentType.DOWNLOAD -> {
                // 📥 下载前先用 72,523 歌名库进行拼音自动纠偏 (如 "木色回想" -> 纠正为 "暮色回响" 再去 B 站搜)
                val match = com.xiaomusic.android.voice.PinyinMatcher.matchAndCorrect(result.keyword, allSongs, context)
                pinyinResult = match
                val targetKeyword = match?.cleanTitle ?: result.keyword
                hasHandledAction = true
                onNavigateToDownload(targetKeyword)
            }

            VoiceIntentType.UNKNOWN -> {
                Toast.makeText(context, "未能理解口令: $text", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 麦克风录音权限 Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceManager.startListening { finalText ->
                handleFinalText(finalText)
            }
        } else {
            Toast.makeText(context, "需要麦克风录音权限才能使用语音控制功能", Toast.LENGTH_LONG).show()
            onDismiss()
        }
    }

    // 进入弹窗时立即检查权限并启动录音
    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            voiceManager.startListening { finalText ->
                handleFinalText(finalText)
            }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 弹窗退出时停止录音
    DisposableEffect(Unit) {
        onDispose {
            voiceManager.stopListening()
        }
    }

    // 涟漪缩放动画
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部标题
                Text(
                    text = "🎤 离线语音控制",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "支持“我想听投食歌”或“我要下载周杰伦”",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 录音动画视觉区域
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(110.dp)
                ) {
                    if (recordState == VoiceRecordState.LISTENING) {
                        // 动态光晕波纹 (结合音量与呼吸脉冲)
                        val dynamicScale = (pulseScale + audioVolume * 0.8f).coerceIn(1f, 1.8f)
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .scale(dynamicScale)
                                .background(
                                    TealPrimary.copy(alpha = 0.2f),
                                    shape = CircleShape
                                )
                        )
                    }

                    // 麦克风核心图标按钮
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(70.dp)
                            .background(
                                if (recordState == VoiceRecordState.LISTENING) TealPrimary else Color.LightGray,
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 状态与识别文字展示区
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (errorMsg != null) {
                        Text(
                            text = errorMsg ?: "",
                            color = Color.Red,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    } else if (commandResult != null) {
                        val result = commandResult!!
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "识别结果: 「${result.rawText}」",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            when (result.type) {
                                VoiceIntentType.PLAY -> {
                                    if (matchedLocalSong != null) {
                                        val py = pinyinResult
                                        if (py != null && py.isCorrected) {
                                            Text(
                                                text = "🎯 拼音自动纠正:「${result.keyword}」->《${py.cleanTitle}》",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TealPrimary,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "▶ 正在播放: $matchedLocalSong",
                                                fontSize = 12.sp,
                                                color = Color.DarkGray,
                                                textAlign = TextAlign.Center
                                            )
                                        } else {
                                            Text(
                                                text = "🎉 已匹配并播放: $matchedLocalSong",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TealPrimary,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "本地未找到「${result.keyword}」",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFD32F2F)
                                        )
                                    }
                                }
                                VoiceIntentType.DOWNLOAD -> {
                                    Text(
                                        text = "📥 正在跳转B站搜索下载「${result.keyword}」...",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BiliPink
                                    )
                                }
                                VoiceIntentType.UNKNOWN -> {
                                    Text(text = "未匹配到具体操作", fontSize = 13.sp)
                                }
                            }
                        }
                    } else if (partialText.isNotBlank()) {
                        Text(
                            text = "“$partialText”",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = if (recordState == VoiceRecordState.INITIALIZING) "正在加载离线模型..." else "请说话，正在聆听中...",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 底部操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 如果识别为点播但本地未搜到，提供【去B站搜索下载】快捷引导
                    if (commandResult?.type == VoiceIntentType.PLAY && matchedLocalSong == null && commandResult?.keyword?.isNotBlank() == true) {
                        Button(
                            onClick = {
                                onNavigateToDownload(commandResult!!.keyword)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BiliPink),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("去B站下载", fontSize = 13.sp)
                        }
                    }

                    if (recordState == VoiceRecordState.LISTENING) {
                        Button(
                            onClick = { voiceManager.stopListening() },
                            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("说完了", fontSize = 13.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(if (recordState == VoiceRecordState.LISTENING || (commandResult?.type == VoiceIntentType.PLAY && matchedLocalSong == null)) 0.8f else 1f)
                    ) {
                        Text("关闭", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
