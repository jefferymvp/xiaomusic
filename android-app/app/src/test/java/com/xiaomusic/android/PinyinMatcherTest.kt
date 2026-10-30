package com.xiaomusic.android

import com.xiaomusic.android.voice.PinyinMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinMatcherTest {

    private val sampleSongs = listOf(
        "01.离别开出花",
        "02.若月亮没来",
        "03.秋风吹起",
        "04.白鸽乌鸦相爱的戏码",
        "05.如果爱忘了",
        "06.你再平凡也是限量版",
        "07.暮色回响",
        "08.天地龙鳞",
        "09.列车开往春天",
        "10.阿嬷",
        "11.爱错",
        "12.壁上观",
        "59.青花瓷",
        "洛天依 投食歌"
    )

    @Test
    fun testPinyinConversion() {
        val pinyin = PinyinMatcher.toPinyin("木色回想")
        assertEquals("musehuixiang", pinyin)

        val targetPinyin = PinyinMatcher.toPinyin("暮色回响")
        assertEquals("musehuixiang", targetPinyin)
    }

    @Test
    fun testUserCaseMuSeHuiXiang() {
        // 用户真实案例：“我要听暮色回想” -> 识别成“木色回想”
        val result = PinyinMatcher.matchAndCorrect("木色回想", sampleSongs)
        assertNotNull("Should match 暮色回响", result)
        assertEquals("07.暮色回响", result?.matchedSong)
        assertEquals("暮色回响", result?.cleanTitle)
        assertTrue("Should be marked as corrected", result?.isCorrected == true)
    }

    @Test
    fun testHomophoneCorrection() {
        // 谐音错别字测试：“清华瓷” -> “59.青花瓷”
        val result = PinyinMatcher.matchAndCorrect("清华瓷", sampleSongs)
        assertNotNull("Should match 青花瓷", result)
        assertEquals("59.青花瓷", result?.matchedSong)
        assertEquals("青花瓷", result?.cleanTitle)
        assertTrue(result?.isCorrected == true)
    }

    @Test
    fun testPartialAndFuzzy() {
        // 部分关键词测试：“投食歌” -> “洛天依 投食歌”
        val result = PinyinMatcher.matchAndCorrect("投食歌", sampleSongs)
        assertNotNull("Should match 洛天依 投食歌", result)
        assertEquals("洛天依 投食歌", result?.matchedSong)
    }
}
