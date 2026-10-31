package com.xiaomusic.android.voice

/**
 * 语音意图类别
 */
enum class VoiceIntentType {
    PLAY,       // 本地曲库点歌播放
    DOWNLOAD,   // B站在线音乐检索并下载
    UNKNOWN     // 未知/无法识别
}

/**
 * 语音识别与意图提取结果
 */
data class VoiceCommandResult(
    val type: VoiceIntentType,
    val keyword: String,
    val rawText: String
)

/**
 * 纯客户端规则级意图解析器
 */
object VoiceIntentParser {

    // 匹配下载意图规则，如：“我要下载投食歌”、“请帮我下载孤勇者”、“从B站下载晴天”、“下载七里香”
    private val DOWNLOAD_PATTERNS = listOf(
        Regex("^(?:我要下载|我想下载|请帮我下载|帮我下载|请下载|去下载|下载一下|下载这首歌|下载首|下载)(.+)"),
        Regex("^(?:从b站下载|在b站下载|b站下载)(.+)"),
        Regex("(.+)(?:这首歌下载|这首下载|下载到手机|下载)$")
    )

    // 匹配播放点歌规则，如：“我想听投食歌”、“我要听晴天”、“请播放晴天”、“放一首七里香”、“来首稻香”、“播放阿嬷”
    private val PLAY_PATTERNS = listOf(
        Regex("^(?:我想听|我要听|想听|请播放|播放|放一首|放首|放一下|放个|放这首|来一首|来首|来一个|听一下|点一首|点首|搜一下|帮我放|帮我播放|放歌)(.+)"),
        Regex("(.+)(?:这首歌|这首|的歌|的音乐)$")
    )

    /**
     * 将 ASR 识别出的原始中文文本解析为结构化意图及核心搜索关键词
     */
    fun parse(rawText: String): VoiceCommandResult {
        // 去除首尾空白与常见标点符号
        val clean = rawText.trim()
            .trimEnd('。', '！', '？', '，', '.', '!', '?', ',')

        if (clean.isBlank()) {
            return VoiceCommandResult(VoiceIntentType.UNKNOWN, "", rawText)
        }

        // 1. 优先匹配下载意图
        for (pattern in DOWNLOAD_PATTERNS) {
            val match = pattern.find(clean)
            if (match != null) {
                var song = match.groupValues[1].trim()
                song = cleanSongSuffix(song)
                if (song.isNotBlank()) {
                    return VoiceCommandResult(VoiceIntentType.DOWNLOAD, song, rawText)
                }
            }
        }

        // 2. 匹配播放点歌意图
        for (pattern in PLAY_PATTERNS) {
            val match = pattern.find(clean)
            if (match != null) {
                var song = match.groupValues[1].trim()
                song = cleanSongSuffix(song)
                if (song.isNotBlank()) {
                    return VoiceCommandResult(VoiceIntentType.PLAY, song, rawText)
                }
            }
        }

        // 3. 兜底策略：如果用户只简单说出歌名（例如“投食歌”、“七里香”），默认作为点播歌曲处理
        val directSong = cleanSongSuffix(clean)
        return VoiceCommandResult(VoiceIntentType.PLAY, directSong, rawText)
    }

    /**
     * 清理常见语气助词与后缀词缀（例如：“投食歌这首歌” -> “投食歌”）
     */
    private fun cleanSongSuffix(song: String): String {
        return song
            .removeSuffix("这首歌")
            .removeSuffix("这首")
            .removeSuffix("这首音乐")
            .removeSuffix("这曲")
            .removeSuffix("这支歌")
            .removeSuffix("的歌")
            .removeSuffix("的音乐")
            .removeSuffix("歌曲")
            .removeSuffix("音乐")
            .removeSuffix("原唱")
            .removeSuffix("吧")
            .removeSuffix("啦")
            .removeSuffix("啊")
            .removeSuffix("呗")
            .removeSuffix("哈")
            .removeSuffix("呀")
            .trim()
    }
}
