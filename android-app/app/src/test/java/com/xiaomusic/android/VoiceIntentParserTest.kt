package com.xiaomusic.android

import com.xiaomusic.android.voice.VoiceIntentParser
import com.xiaomusic.android.voice.VoiceIntentType
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIntentParserTest {

    @Test
    fun testPlayIntent() {
        val cases = listOf(
            "我想听投食歌" to "投食歌",
            "我想听投食歌这首歌" to "投食歌",
            "请播放晴天" to "晴天",
            "放一首七里香" to "七里香",
            "来首稻香吧" to "稻香",
            "听一下阿嬷" to "阿嬷",
            "投食歌" to "投食歌"
        )

        for ((input, expectedKeyword) in cases) {
            val result = VoiceIntentParser.parse(input)
            assertEquals("Input: $input", VoiceIntentType.PLAY, result.type)
            assertEquals("Keyword for: $input", expectedKeyword, result.keyword)
        }
    }

    @Test
    fun testDownloadIntent() {
        val cases = listOf(
            "我要下载投食歌" to "投食歌",
            "我要下载投食歌这首歌" to "投食歌",
            "请帮我下载孤勇者" to "孤勇者",
            "从b站下载晴天" to "晴天",
            "下载七里香" to "七里香",
            "帮我下载周杰伦" to "周杰伦"
        )

        for ((input, expectedKeyword) in cases) {
            val result = VoiceIntentParser.parse(input)
            assertEquals("Input: $input", VoiceIntentType.DOWNLOAD, result.type)
            assertEquals("Keyword for: $input", expectedKeyword, result.keyword)
        }
    }
}
