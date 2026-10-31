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

    @Test
    fun testWordLevelCorrection() {
        // 词语后向纠偏映射测试：“到香” -> 命中本地“稻香”（若本地有）或直接纠偏
        val sampleWithDaoXiang = sampleSongs + "稻香"
        val result = PinyinMatcher.matchAndCorrect("到香", sampleWithDaoXiang)
        assertNotNull("Should match 稻香", result)
        assertEquals("稻香", result?.cleanTitle)
        assertTrue(result?.isCorrected == true)
    }

    @Test
    fun testDialectFuzzyPinyin() {
        // 方言模糊音测试：前后鼻音容错，将 "青花瓷" 读作 "亲花瓷" (qinhuaci -> qinghuaci)
        val result = PinyinMatcher.matchAndCorrect("亲花瓷", sampleSongs)
        assertNotNull("Should match 青花瓷 via fuzzy pinyin", result)
        assertEquals("59.青花瓷", result?.matchedSong)
        assertEquals("青花瓷", result?.cleanTitle)
        assertTrue(result?.isCorrected == true)
    }

    @Test
    fun testArtistSongSeparation() {
        // 歌手与歌名组合：“周杰伦的青花瓷” -> 提取并命中本地 “59.青花瓷”
        val result = PinyinMatcher.matchAndCorrect("周杰伦的青花瓷", sampleSongs)
        assertNotNull("Should match 青花瓷", result)
        assertEquals("59.青花瓷", result?.matchedSong)
        assertEquals("青花瓷", result?.cleanTitle)
    }

    @Test
    fun testZCSFlatAndRetroflexFuzzy() {
        // 平翘舌音深度容错测试：
        // 1. 用户说翘舌，模型出平舌: "暮色回响"(se) 识别为 "暮社回响"(she, 翘舌)
        val resultShe = PinyinMatcher.matchAndCorrect("暮社回响", sampleSongs)
        assertNotNull("Should match 暮色回响 via retroflex fuzzy", resultShe)
        assertEquals("07.暮色回响", resultShe?.matchedSong)
        assertEquals("暮色回响", resultShe?.cleanTitle)

        // 2. "青花瓷"(ci, 平舌) 识别为 "青花池"(chi, 翘舌)
        val resultChi = PinyinMatcher.matchAndCorrect("青花池", sampleSongs)
        assertNotNull("Should match 青花瓷 via flat-retroflex fuzzy", resultChi)
        assertEquals("59.青花瓷", resultChi?.matchedSong)
        assertEquals("青花瓷", resultChi?.cleanTitle)
    }

    @Test
    fun testCorrectedDisplayTextGeneration() {
        // 观感优化测试：将错别字整句先纠正为规范歌名
        val corrected1 = PinyinMatcher.buildCorrectedDisplayText(
            rawFullText = "我想听木色回想这首歌",
            originalKeyword = "木色回想",
            cleanTitle = "暮色回响"
        )
        assertEquals("我想听《暮色回响》这首歌", corrected1)

        val corrected2 = PinyinMatcher.buildCorrectedDisplayText(
            rawFullText = "播放青花池",
            originalKeyword = "青花池",
            cleanTitle = "青花瓷"
        )
        assertEquals("播放《青花瓷》", corrected2)
    }
}
