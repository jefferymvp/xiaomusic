# XiaoMusic Android 语音控制与全量歌名拼音纠偏开发归档文档

> **归档时间戳**: 2026-10-30 16:35:03  
> **归档位置**: `process/voice_control_implementation_20261030_163503.md`  
> **开发范围**: 仅 Android 客户端（未改动任何 Python 服务端代码）

---

## 1. 需求背景与核心目标

用户希望在 XiaoMusic Android 客户端中集成纯离线语音控制功能，解决车载、居家等免动手点歌与找歌场景。同时针对语音识别常出现的“同音字/谐音字偏差”（例如将“暮色回响”识别成“木色回想”导致搜不到歌）以及本地音乐较少时的识别纠错瓶颈，提出以下具体目标：

1. **双意图语音交互**：
   - **点歌播放意图**：用户说“我想听投食歌”、“播放暮色回响”，自动提取歌名并在本地/服务端曲库中匹配，命中后直接拉起播放。
   - **下载搜索意图**：用户说“我要下载xxx歌”，自动弹出 B 站搜索下载界面，自动带入提取出的歌名发起检索供用户下载。
2. **纯客户端拼音自动纠偏**：
   - 针对语音识别误字（如“木色回想”），将识别词与曲库歌名统一转换为全拼（`musehuixiang`），比对命中后使用真实标准歌名（《暮色回响》）进行修正。
3. **集成 102,197 首全量华语歌名大库（无截断、全量提取）**：
   - 引入开源仓库 [dengxiuqi/ChineseLyrics](https://github.com/dengxiuqi/ChineseLyrics)，全量下载并解析全部 5 个 JSON 数据文件（共 102,197 首歌词记录）。
   - 去重提取出 **全部 72,523 首独立歌名**，无任何“精选截断”或过滤，构建高覆盖率的离线歌名词库。

---

## 2. 系统架构与关键实现

### 2.1 离线 ASR 引擎集成 (`sherpa-onnx`)
- **依赖配置**:
  - `settings.gradle.kts`: 引入 JitPack 镜像源 `https://jitpack.io`。
  - `app/build.gradle.kts`: 引入 `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8@aar` 与 `com.belerweb:pinyin4j:2.5.1`。
- **离线模型内置**:
  - 存放于 `app/src/main/assets/sherpa-onnx/`：
    - `tokens.txt` (48 KB)
    - `encoder-epoch-99-avg-1.int8.onnx` (21.6 MB)
    - `decoder-epoch-99-avg-1.onnx` (7.5 MB)
    - `joiner-epoch-99-avg-1.int8.onnx` (1.8 MB)
- **录音与流式解码 (`SherpaVoiceManager.kt`)**:
  - `AudioRecord` 以 16kHz PCM 单声道流式采集音频；
  - 采用流式 Transducer 实时送入 `OnlineStream`，支持 Endpoint 静音检测自动截断识别。

### 2.2 意图解析器 (`VoiceIntentParser.kt`)
- 支持指令前缀识别与槽位提取：
  - `PLAY`（播放）：支持“我想听”、“我要听”、“播放”、“来一首”、“放一下”等；
  - `DOWNLOAD`（下载）：支持“我要下载”、“我想下载”、“帮我下载”、“下载”等；
  - 自动剥离尾缀（如“的歌”、“这首歌”）。

### 2.3 拼音匹配与全量倒排索引 (`PinyinMatcher.kt`)
- **双优先级机制**：
  1. **第一优先级（本地曲库）**：遍历本地已下载或播放列表文件，支持去除前缀序号（如 `07.`）、扩展名（如 `.mp3`）后的汉字比对、全拼一致比对、包含匹配；
  2. **第二优先级（全量 72,523 首大库）**：本地无匹配时，通过 $O(1)$ 的拼音倒排哈希索引（`pinyinToSongMap`）毫秒级检索出标准中文歌名，用于精准下载。
- **性能优化**：
  - 在 `XiaoMusicApplication.kt` 启动时通过 Coroutine 后台异步预热加载 `assets/song_lexicon.txt`，冷启动完全零卡顿。

### 2.4 UI 交互设计 (`VoiceControlDialog.kt` & `HomeScreen.kt`)
- 搜索栏右侧内嵌麦克风快捷入口，屏幕右下角提供 FAB 悬浮麦克风按钮；
- 弹窗展示实时动态涟漪波形动效、识别文本、拼音自动纠偏横幅（如“🎯 拼音自动纠正：‘木色回想’ $\to$ 《暮色回响》”）；
- 本地无匹配但命中全量词库时，一键跳转到 B 站下载页面并自动填入标准歌名检索。

---

## 3. 全量数据提取与验证

### 3.1 提取脚本 (`android-app/extract_all_songs.py`)
- 全量下载解析 `dengxiuqi/ChineseLyrics` 的 `lyrics1.json` ~ `lyrics5.json`（共 160MB+）；
- 提取去重后生成 `app/src/main/assets/song_lexicon.txt`（1.15 MB，共 72,523 行），确保所有歌名 100% 覆盖。

### 3.2 自动化测试
- **单元测试**：
  - `VoiceIntentParserTest.kt`: 覆盖点歌意图、下载意图与关键词提取（100% 通过）。
  - `PinyinMatcherTest.kt`: 覆盖“木色回想” $\to$ “暮色回响”、“清华瓷” $\to$ “青花瓷”谐音纠偏（100% 通过）。
- **Gradle 验证**：
  - 执行 `.\gradlew.bat testDebugUnitTest`，全部测试任务执行成功，耗时 26 秒，`BUILD SUCCESSFUL`。

---

## 4. 后续规划与待办事项 (TODO)

1. **APK 体积针对性优化**：
   - 当前内置多架构 native 动态库与模型导致 Fat APK 较大，后续可通过 `splits { abi { ... } }` 按架构生成独立包（例如仅保留 `arm64-v8a`，包体积可大幅压缩至 40MB 以内）。
2. **模糊音与方言支持**：
   - 针对平翘舌音（z/zh, c/ch, s/sh）及前后鼻音（en/eng, in/ing）增加拼音映射归一化规则，进一步提升方言识别容错。
