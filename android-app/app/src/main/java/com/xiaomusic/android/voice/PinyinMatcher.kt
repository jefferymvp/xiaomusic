package com.xiaomusic.android.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.min

/**
 * 拼音匹配与自动纠错结果
 */
data class PinyinMatchResult(
    val matchedSong: String,        // 本地曲库中的真实文件名 (如 "07.暮色回响"，若本地无则为空字符串)
    val cleanTitle: String,         // 纠正后的标准中文规范歌名 (如 "暮色回响")
    val isCorrected: Boolean,       // 是否为拼音自动纠偏命中
    val isLocalAvailable: Boolean,  // 本地当前是否有该歌曲文件
    val originalKeyword: String,    // 用户原始语音关键词 (如 "木色回想")
    val confidence: Float           // 匹配置信度 (0.0 ~ 1.0)
)

/**
 * 纯客户端拼音转换与模糊纠错匹配器 (支持本地曲库 + 72,523 首全量华语歌名大库)
 */
object PinyinMatcher {

    private const val TAG = "PinyinMatcher"
    private const val LEXICON_ASSET_PATH = "song_lexicon.txt"

    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    // 72,523 首全量歌名词库缓存及倒排拼音索引 (pinyin -> 标准中文歌名，使用并发容器防止多线程冲突崩溃)
    @Volatile
    private var isLexiconLoaded = false
    private val globalLexiconList = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val pinyinToSongMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val fuzzyPinyinToSongMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    // 常见语音识别易错词后向映射表 (用于在拼音比对前做快速词语纠正)
    val COMMON_CORRECTIONS = mapOf(
        "木色回想" to "暮色回响",
        "牧色回想" to "暮色回响",
        "木色回响" to "暮色回响",
        "牧色回响" to "暮色回响",
        "清华瓷" to "青花瓷",
        "亲花瓷" to "青花瓷",
        "情花瓷" to "青花瓷",
        "青花池" to "青花瓷",
        "青花迟" to "青花瓷",
        "到香" to "稻香",
        "道香" to "稻香",
        "大鱼海塘" to "大鱼海棠",
        "告白汽球" to "告白气球",
        "七理香" to "七里香",
        "其里香" to "七里香",
        "投石歌" to "投食歌",
        "透食歌" to "投食歌",
        "落天依" to "洛天依",
        "晴田" to "晴天",
        "秦天" to "晴天",
        "起风啦" to "起风了",
        "孤勇者" to "孤勇者",
        "赤灵" to "赤伶",
        "次陵" to "赤伶",
        "白鸽乌鸦" to "白鸽乌鸦相爱的戏码"
    )

    // 常见歌名多音字标准发音覆盖 (防止被 pinyin4j 默认非音乐读音带偏)
    private val POLYPHONE_MAP = mapOf(
        '乐' to "yue",
        '行' to "xing",
        '长' to "chang",
        '重' to "chong",
        '曲' to "qu",
        '都' to "dou",
        '着' to "zhe",
        '得' to "de"
    )

    /**
     * 异步后台预加载 assets/song_lexicon.txt 全量歌名词库
     */
    fun preheatLexiconAsync(context: Context) {
        if (isLexiconLoaded) return
        CoroutineScope(Dispatchers.IO).launch {
            loadLexicon(context)
        }
    }

    /**
     * 同步加载 7 万首全量歌名数据库
     */
    @Synchronized
    fun loadLexicon(context: Context) {
        if (isLexiconLoaded) return
        try {
            val start = System.currentTimeMillis()
            context.assets.open(LEXICON_ASSET_PATH).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            globalLexiconList.add(trimmed)
                            val pinyin = toPinyin(trimmed)
                            if (pinyin.isNotEmpty()) {
                                if (!pinyinToSongMap.containsKey(pinyin)) {
                                    pinyinToSongMap[pinyin] = trimmed
                                }
                                val fuzzy = toFuzzyPinyin(pinyin)
                                if (fuzzy.isNotEmpty() && !fuzzyPinyinToSongMap.containsKey(fuzzy)) {
                                    fuzzyPinyinToSongMap[fuzzy] = trimmed
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
            isLexiconLoaded = true
            Log.i(TAG, "已加载 72,523 全量歌名数据库！词条数: ${globalLexiconList.size}, 拼音索引数: ${pinyinToSongMap.size}, 耗时: ${System.currentTimeMillis() - start}ms")
        } catch (e: Exception) {
            Log.e(TAG, "加载 song_lexicon.txt 失败: ${e.message}")
        }
    }

    /**
     * 将中文文本转换为拼音连续全拼 (如 "木色回想" -> "musehuixiang")
     */
    fun toPinyin(text: String): String {
        val clean = cleanText(text)
        val sb = StringBuilder()
        for (char in clean) {
            val special = POLYPHONE_MAP[char]
            if (special != null) {
                sb.append(special)
            } else if (char.toString().matches(Regex("[\\u4e00-\\u9fa5]"))) {
                try {
                    val pinyinArray = PinyinHelper.toHanyuPinyinStringArray(char, pinyinFormat)
                    if (!pinyinArray.isNullOrEmpty()) {
                        sb.append(pinyinArray[0])
                    } else {
                        sb.append(char)
                    }
                } catch (e: Exception) {
                    sb.append(char)
                }
            } else if (char.isLetterOrDigit()) {
                sb.append(char.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * 将拼音转换为平翘舌、前后鼻音、边鼻音、方言混淆深度归一化后的模糊拼音
     * 1. 平翘舌互通: zh -> z, ch -> c, sh -> s
     * 2. 前后鼻音互通: ang -> an, eng -> en, ing -> in
     * 3. 边鼻音互通: l -> n
     * 4. 唇齿与舌根音互通: f -> h
     * 5. 卷舌与边音互通: r -> l
     * 6. 介母弱化: uang/iang/uan -> an
     */
    fun toFuzzyPinyin(pinyin: String): String {
        return pinyin
            .replace("zh", "z")
            .replace("ch", "c")
            .replace("sh", "s")
            .replace("ang", "an")
            .replace("eng", "en")
            .replace("ing", "in")
            .replace("l", "n")
            .replace("f", "h")
            .replace("r", "l")
            .replace("uang", "an")
            .replace("iang", "an")
            .replace("uan", "an")
    }

    /**
     * 计算两个拼音之间的莱文斯坦编辑距离 (Levenshtein Distance)
     */
    fun editDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,       // 删除
                    min(
                        dp[i][j - 1] + 1,   // 插入
                        dp[i - 1][j - 1] + cost // 替换
                    )
                )
            }
        }
        return dp[m][n]
    }

    /**
     * 计算归一化拼音相似度 (0.0 ~ 1.0)
     */
    fun pinyinSimilarity(p1: String, p2: String): Float {
        if (p1 == p2) return 1.0f
        val maxLen = max(p1.length, p2.length)
        if (maxLen == 0) return 1.0f
        val dist = editDistance(p1, p2)
        return (1.0f - dist.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * 将原始语音全句中的错词先纠正为标准歌名，以提升界面的视觉观感
     * 例如："我想听木色回想这首歌" -> "我想听《暮色回响》这首歌"
     */
    fun buildCorrectedDisplayText(rawFullText: String, originalKeyword: String, cleanTitle: String): String {
        if (rawFullText.isBlank()) return "《$cleanTitle》"
        val cleanKw = cleanText(originalKeyword)
        if (cleanKw.isNotBlank() && rawFullText.contains(cleanKw)) {
            return rawFullText.replace(cleanKw, "《$cleanTitle》")
        }
        if (rawFullText.contains(originalKeyword)) {
            return rawFullText.replace(originalKeyword, "《$cleanTitle》")
        }
        return "播放《$cleanTitle》"
    }

    /**
     * 去除前缀序号（如 "01."、"07."、"【无损】"）及扩展名（如 ".mp3"）提取纯净歌名
     */
    fun extractCleanTitle(rawName: String): String {
        return rawName
            .replace(Regex("^[0-9]+[.\\s、_-]+"), "")
            .replace(Regex("\\.(mp3|flac|wav|m4a|aac|ogg|ape)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^[【\\[(].*?[】\\])]\\s*"), "")
            .trim()
    }

    /**
     * 清理标点符号与特殊字符
     */
    fun cleanText(text: String): String {
        return text
            .replace(Regex("[\\s.,/#!$%^&*;:{}=\\-_`~()？?！!，。、“”\"'【】\\[\\]]+"), "")
            .trim()
    }

    /**
     * 智能拼音比对与自动纠错
     * 1. 词语级后向纠正 (COMMON_CORRECTIONS 常见语音混淆词对齐)；
     * 2. 优先在本地曲库 (localSongs) 中比对 (汉字精准/包含 -> 拼音全等 -> 方言模糊拼音全等 -> 拼音包含)；
     * 3. 若本地无此歌，在 72,523 首全量歌名词库中检索 (标准倒排索引 -> 方言模糊倒排索引 -> 汉字包含)。
     */
    fun matchAndCorrect(
        keyword: String,
        localSongs: List<String>,
        context: Context? = null
    ): PinyinMatchResult? {
        val initialClean = cleanText(keyword)
        if (initialClean.isBlank()) return null

        // 1. 后向词语纠错：检查是否有已知易错词快速映射
        val correctedByWord = COMMON_CORRECTIONS[initialClean]
        val cleanKeyword = correctedByWord ?: initialClean
        val isWordLevelCorrected = correctedByWord != null

        val keywordPinyin = toPinyin(cleanKeyword)
        val keywordFuzzyPinyin = toFuzzyPinyin(keywordPinyin)

        // 2. 歌手-歌名辅助拆分 (如 "周杰伦的青花瓷" 提取出备选纯歌名 "青花瓷")
        val altKeywords = mutableListOf<String>()
        if (cleanKeyword.contains("的") && cleanKeyword.length > 2) {
            val parts = cleanKeyword.split("的")
            if (parts.size >= 2 && parts.last().isNotBlank()) {
                altKeywords.add(parts.last())
            }
        }

        // ==================== 第一优先级：本地已下载曲库比对 ====================
        if (localSongs.isNotEmpty()) {
            for (rawSong in localSongs) {
                val cleanTitle = extractCleanTitle(rawSong)
                val titleCleaned = cleanText(cleanTitle)
                val titlePinyin = toPinyin(titleCleaned)
                val titleFuzzyPinyin = toFuzzyPinyin(titlePinyin)

                // 1. 本地汉字完全一致 / 互相包含
                if (titleCleaned.equals(cleanKeyword, ignoreCase = true)) {
                    return PinyinMatchResult(
                        matchedSong = rawSong,
                        cleanTitle = cleanTitle,
                        isCorrected = isWordLevelCorrected,
                        isLocalAvailable = true,
                        originalKeyword = keyword,
                        confidence = 1.0f
                    )
                }

                if (titleCleaned.contains(cleanKeyword, ignoreCase = true) || cleanKeyword.contains(titleCleaned, ignoreCase = true)) {
                    return PinyinMatchResult(
                        matchedSong = rawSong,
                        cleanTitle = cleanTitle,
                        isCorrected = isWordLevelCorrected,
                        isLocalAvailable = true,
                        originalKeyword = keyword,
                        confidence = 0.95f
                    )
                }

                // 备选纯歌名命中（如用户说"周杰伦的青花瓷"，本地歌名为"青花瓷"）
                for (alt in altKeywords) {
                    if (titleCleaned.equals(alt, ignoreCase = true) || titleCleaned.contains(alt, ignoreCase = true)) {
                        return PinyinMatchResult(
                            matchedSong = rawSong,
                            cleanTitle = cleanTitle,
                            isCorrected = true,
                            isLocalAvailable = true,
                            originalKeyword = keyword,
                            confidence = 0.94f
                        )
                    }
                }

                // 2. 本地拼音完全一致 (如 "木色回想" 的拼音 == "暮色回响" 的拼音)
                if (keywordPinyin.isNotEmpty() && keywordPinyin == titlePinyin) {
                    return PinyinMatchResult(
                        matchedSong = rawSong,
                        cleanTitle = cleanTitle,
                        isCorrected = !titleCleaned.equals(cleanKeyword, ignoreCase = true) || isWordLevelCorrected,
                        isLocalAvailable = true,
                        originalKeyword = keyword,
                        confidence = 0.92f
                    )
                }

                // 3. 本地方言/模糊拼音完全一致 (如平翘舌/前后鼻音口音容错)
                if (keywordFuzzyPinyin.isNotEmpty() && keywordFuzzyPinyin == titleFuzzyPinyin) {
                    return PinyinMatchResult(
                        matchedSong = rawSong,
                        cleanTitle = cleanTitle,
                        isCorrected = true,
                        isLocalAvailable = true,
                        originalKeyword = keyword,
                        confidence = 0.88f
                    )
                }

                // 4. 本地拼音子串包含
                if (keywordPinyin.isNotEmpty() && (titlePinyin.contains(keywordPinyin) || keywordPinyin.contains(titlePinyin))) {
                    return PinyinMatchResult(
                        matchedSong = rawSong,
                        cleanTitle = cleanTitle,
                        isCorrected = !titleCleaned.equals(cleanKeyword, ignoreCase = true),
                        isLocalAvailable = true,
                        originalKeyword = keyword,
                        confidence = 0.85f
                    )
                }
            }

            // 5. 本地模糊拼音编辑距离相似度比对 (平翘舌、发音轻重、吞音漏字全面容错，阈值 >= 0.70)
            var bestSim = 0.0f
            var bestMatchSong: String? = null
            var bestMatchTitle: String? = null

            for (rawSong in localSongs) {
                val cleanTitle = extractCleanTitle(rawSong)
                val titleCleaned = cleanText(cleanTitle)
                val titlePinyin = toPinyin(titleCleaned)
                val titleFuzzyPinyin = toFuzzyPinyin(titlePinyin)

                val sim = pinyinSimilarity(keywordFuzzyPinyin, titleFuzzyPinyin)
                if (sim >= 0.70f && sim > bestSim) {
                    bestSim = sim
                    bestMatchSong = rawSong
                    bestMatchTitle = cleanTitle
                }
            }

            if (bestMatchSong != null && bestMatchTitle != null) {
                return PinyinMatchResult(
                    matchedSong = bestMatchSong,
                    cleanTitle = bestMatchTitle,
                    isCorrected = true,
                    isLocalAvailable = true,
                    originalKeyword = keyword,
                    confidence = (bestSim * 0.88f).coerceIn(0.75f, 0.90f)
                )
            }
        }

        // ==================== 第二优先级：72,523 首全量歌名词库比对纠错 ====================
        if (context != null && !isLexiconLoaded) {
            loadLexicon(context)
        }

        if (pinyinToSongMap.isNotEmpty()) {
            // 1. 标准拼音倒排索引 $O(1)$ 常数时间完全匹配
            if (keywordPinyin.isNotEmpty()) {
                val exactSong = pinyinToSongMap[keywordPinyin]
                if (exactSong != null) {
                    return PinyinMatchResult(
                        matchedSong = "",
                        cleanTitle = exactSong,
                        isCorrected = !cleanText(exactSong).equals(cleanKeyword, ignoreCase = true) || isWordLevelCorrected,
                        isLocalAvailable = false,
                        originalKeyword = keyword,
                        confidence = 0.90f
                    )
                }
            }

            // 2. 方言模糊音倒排索引 $O(1)$ 常数时间匹配
            if (keywordFuzzyPinyin.isNotEmpty()) {
                val fuzzySong = fuzzyPinyinToSongMap[keywordFuzzyPinyin]
                if (fuzzySong != null) {
                    return PinyinMatchResult(
                        matchedSong = "",
                        cleanTitle = fuzzySong,
                        isCorrected = true,
                        isLocalAvailable = false,
                        originalKeyword = keyword,
                        confidence = 0.86f
                    )
                }
            }

            // 3. 汉字完全包含于大库
            for (song in globalLexiconList) {
                if (song.contains(cleanKeyword, ignoreCase = true) || cleanKeyword.contains(song, ignoreCase = true)) {
                    return PinyinMatchResult(
                        matchedSong = "",
                        cleanTitle = song,
                        isCorrected = isWordLevelCorrected,
                        isLocalAvailable = false,
                        originalKeyword = keyword,
                        confidence = 0.80f
                    )
                }
            }
        }

        return null
    }

    /**
     * 手动注入词条 (用于单元测试)
     */
    fun injectForTesting(songs: List<String>) {
        globalLexiconList.clear()
        pinyinToSongMap.clear()
        fuzzyPinyinToSongMap.clear()
        for (s in songs) {
            globalLexiconList.add(s)
            val p = toPinyin(s)
            if (p.isNotEmpty()) {
                if (!pinyinToSongMap.containsKey(p)) {
                    pinyinToSongMap[p] = s
                }
                val fuzzy = toFuzzyPinyin(p)
                if (fuzzy.isNotEmpty() && !fuzzyPinyinToSongMap.containsKey(fuzzy)) {
                    fuzzyPinyinToSongMap[fuzzy] = s
                }
            }
        }
        isLexiconLoaded = true
    }
}
