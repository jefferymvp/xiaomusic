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

    // 72,523 首全量歌名词库缓存及倒排拼音索引 (pinyin -> 标准中文歌名)
    private var isLexiconLoaded = false
    private val globalLexiconList = mutableListOf<String>()
    private val pinyinToSongMap = HashMap<String, String>()

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
                            if (pinyin.isNotEmpty() && !pinyinToSongMap.containsKey(pinyin)) {
                                pinyinToSongMap[pinyin] = trimmed
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
            if (char.toString().matches(Regex("[\\u4e00-\\u9fa5]"))) {
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
     * 1. 优先在本地曲库 (localSongs) 中比对，本地命中则可直接播放；
     * 2. 若本地无此歌，在 72,523 首全量歌名词库中进行拼音纠正，返回标准歌名供去 B 站精准搜索下载。
     */
    fun matchAndCorrect(
        keyword: String,
        localSongs: List<String>,
        context: Context? = null
    ): PinyinMatchResult? {
        val cleanKeyword = cleanText(keyword)
        if (cleanKeyword.isBlank()) return null

        val keywordPinyin = toPinyin(cleanKeyword)

        // ==================== 第一优先级：本地已下载曲库比对 ====================
        if (localSongs.isNotEmpty()) {
            for (rawSong in localSongs) {
                val cleanTitle = extractCleanTitle(rawSong)
                val titleCleaned = cleanText(cleanTitle)
                val titlePinyin = toPinyin(titleCleaned)

                // 1. 本地汉字完全一致 / 互相包含
                if (titleCleaned.equals(cleanKeyword, ignoreCase = true)) {
                    return PinyinMatchResult(
                        matchedSong = rawSong,
                        cleanTitle = cleanTitle,
                        isCorrected = false,
                        isLocalAvailable = true,
                        originalKeyword = keyword,
                        confidence = 1.0f
                    )
                }

                if (titleCleaned.contains(cleanKeyword, ignoreCase = true) || cleanKeyword.contains(titleCleaned, ignoreCase = true)) {
                    return PinyinMatchResult(
                        matchedSong = rawSong,
                        cleanTitle = cleanTitle,
                        isCorrected = false,
                        isLocalAvailable = true,
                        originalKeyword = keyword,
                        confidence = 0.95f
                    )
                }

                // 2. 本地拼音完全一致 (如 "木色回想" 的拼音 == "暮色回响" 的拼音)
                if (keywordPinyin.isNotEmpty() && keywordPinyin == titlePinyin) {
                    return PinyinMatchResult(
                        matchedSong = rawSong,
                        cleanTitle = cleanTitle,
                        isCorrected = !titleCleaned.equals(cleanKeyword, ignoreCase = true),
                        isLocalAvailable = true,
                        originalKeyword = keyword,
                        confidence = 0.92f
                    )
                }

                // 3. 本地拼音子串包含
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
        }

        // ==================== 第二优先级：72,523 首全量歌名词库比对纠错 ====================
        if (context != null && !isLexiconLoaded) {
            loadLexicon(context)
        }

        if (pinyinToSongMap.isNotEmpty()) {
            // 1. 倒排索引 $O(1)$ 常数时间完全匹配
            if (keywordPinyin.isNotEmpty()) {
                val exactSong = pinyinToSongMap[keywordPinyin]
                if (exactSong != null) {
                    return PinyinMatchResult(
                        matchedSong = "",
                        cleanTitle = exactSong,
                        isCorrected = !cleanText(exactSong).equals(cleanKeyword, ignoreCase = true),
                        isLocalAvailable = false,
                        originalKeyword = keyword,
                        confidence = 0.90f
                    )
                }
            }

            // 2. 汉字完全包含于大库
            for (song in globalLexiconList) {
                if (song.contains(cleanKeyword, ignoreCase = true) || cleanKeyword.contains(song, ignoreCase = true)) {
                    return PinyinMatchResult(
                        matchedSong = "",
                        cleanTitle = song,
                        isCorrected = false,
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
        for (s in songs) {
            globalLexiconList.add(s)
            val p = toPinyin(s)
            if (p.isNotEmpty() && !pinyinToSongMap.containsKey(p)) {
                pinyinToSongMap[p] = s
            }
        }
        isLexiconLoaded = true
    }
}
