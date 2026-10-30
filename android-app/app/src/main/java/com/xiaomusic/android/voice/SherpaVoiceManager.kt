package com.xiaomusic.android.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 录音状态
 */
enum class VoiceRecordState {
    IDLE,           // 空闲
    INITIALIZING,   // 正在初始化引擎
    LISTENING,      // 正在聆听录音
    PROCESSING,     // 正在解析处理
    ERROR           // 发生错误
}

/**
 * 基于 sherpa-onnx 的离线语音识别与采集管理器
 */
class SherpaVoiceManager(private val context: Context) {

    companion object {
        private const val TAG = "SherpaVoiceManager"
        private const val SAMPLE_RATE = 16000
        private const val ASSETS_MODEL_DIR = "sherpa-onnx"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var recognizer: OnlineRecognizer? = null
    private var isModelLoaded = false

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null

    // 状态流
    private val _state = MutableStateFlow(VoiceRecordState.IDLE)
    val state: StateFlow<VoiceRecordState> = _state.asStateFlow()

    // 实时转录文本
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    // 当前音频振幅 (0.0 ~ 1.0，用于 UI 声波动画)
    private val _audioVolume = MutableStateFlow(0f)
    val audioVolume: StateFlow<Float> = _audioVolume.asStateFlow()

    // 错误信息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 异步预初始化引擎 (在后台线程加载 ONNX 模型)
     */
    fun preheatAsync() {
        if (isModelLoaded) return
        scope.launch {
            initEngine()
        }
    }

    /**
     * 初始化 sherpa-onnx OnlineRecognizer
     */
    @Synchronized
    private fun initEngine(): Boolean {
        if (isModelLoaded && recognizer != null) return true

        try {
            _state.value = VoiceRecordState.INITIALIZING
            Log.i(TAG, "正在从 assets/$ASSETS_MODEL_DIR 加载离线 sherpa-onnx 语音模型...")

            val transducerConfig = OnlineTransducerModelConfig(
                encoder = "$ASSETS_MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx",
                decoder = "$ASSETS_MODEL_DIR/decoder-epoch-99-avg-1.onnx",
                joiner = "$ASSETS_MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
            )

            val modelConfig = OnlineModelConfig(
                transducer = transducerConfig,
                tokens = "$ASSETS_MODEL_DIR/tokens.txt",
                modelType = "zipformer",
                numThreads = 2,
                provider = "cpu",
                debug = false
            )

            val recognizerConfig = OnlineRecognizerConfig(
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
                enableEndpoint = true
            )

            recognizer = OnlineRecognizer(
                assetManager = context.assets,
                config = recognizerConfig
            )

            isModelLoaded = true
            _state.value = VoiceRecordState.IDLE
            Log.i(TAG, "sherpa-onnx 离线模型初始化完成！")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "初始化语音识别模型失败: ${e.message}", e)
            _errorMessage.value = "初始化语音模型失败: ${e.localizedMessage}"
            _state.value = VoiceRecordState.ERROR
            return false
        }
    }

    /**
     * 开启麦克风录音并开始实时识别
     * @param onFinalResult 最终识别完成时的回调 (返回识别文本)
     */
    @SuppressLint("MissingPermission")
    fun startListening(onFinalResult: (String) -> Unit) {
        if (isRecording.get()) return

        scope.launch {
            if (!initEngine()) {
                return@launch
            }

            val currentRecognizer = recognizer ?: return@launch
            _partialText.value = ""
            _audioVolume.value = 0f
            _errorMessage.value = null
            _state.value = VoiceRecordState.LISTENING

            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE / 10 * 2)

            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    _errorMessage.value = "无法初始化麦克风录音设备"
                    _state.value = VoiceRecordState.ERROR
                    return@launch
                }

                audioRecord = record
                record.startRecording()
                isRecording.set(true)

                val stream = currentRecognizer.createStream()

                recordingJob = launch {
                    val buffer = ShortArray(minBufferSize / 2)
                    var lastResultText = ""

                    try {
                        while (isActive && isRecording.get()) {
                            val readCount = record.read(buffer, 0, buffer.size)
                            if (readCount > 0) {
                                // 1. 计算当前振幅与音量大小
                                var maxAmp = 0
                                for (i in 0 until readCount) {
                                    val absVal = abs(buffer[i].toInt())
                                    if (absVal > maxAmp) maxAmp = absVal
                                }
                                val vol = (maxAmp / 32768.0f).coerceIn(0f, 1f)
                                _audioVolume.value = vol

                                // 2. 转换为归一化 FloatArray (-1.0 ~ 1.0)
                                val floatArray = FloatArray(readCount) { idx ->
                                    buffer[idx] / 32768.0f
                                }

                                // 3. 送入 sherpa-onnx stream 解码
                                stream.acceptWaveform(floatArray, SAMPLE_RATE)
                                while (currentRecognizer.isReady(stream)) {
                                    currentRecognizer.decode(stream)
                                }

                                val result = currentRecognizer.getResult(stream)
                                val currentText = result.text.trim()
                                if (currentText.isNotEmpty() && currentText != lastResultText) {
                                    lastResultText = currentText
                                    _partialText.value = currentText
                                    Log.d(TAG, "实时识别文字: $currentText")
                                }

                                // 4. 静音断句检测 (Endpoint)
                                if (currentRecognizer.isEndpoint(stream)) {
                                    Log.i(TAG, "检测到说话结束 (Endpoint)")
                                    if (lastResultText.isNotBlank()) {
                                        break
                                    } else {
                                        currentRecognizer.reset(stream)
                                    }
                                }
                            }
                        }
                    } finally {
                        // 结束采音并收尾
                        isRecording.set(false)
                        val finalResult = currentRecognizer.getResult(stream).text.trim().ifEmpty { lastResultText }
                        stream.release()

                        withContext(Dispatchers.Main) {
                            _audioVolume.value = 0f
                            _state.value = VoiceRecordState.PROCESSING
                            onFinalResult(finalResult)
                            _state.value = VoiceRecordState.IDLE
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "录音识别异常: ${e.message}", e)
                _errorMessage.value = "录音失败: ${e.localizedMessage}"
                _state.value = VoiceRecordState.ERROR
                stopListening()
            }
        }
    }

    /**
     * 手动停止录音 (如用户松开长按按钮或点击完成)
     */
    fun stopListening() {
        if (!isRecording.getAndSet(false)) return
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "停止麦克风异常: ${e.message}")
        }
        recordingJob?.cancel()
    }

    /**
     * 释放所有底层资源
     */
    fun release() {
        stopListening()
        recognizer?.release()
        recognizer = null
        isModelLoaded = false
    }
}
