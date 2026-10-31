import os
import soundfile as sf
import sherpa_onnx

def run_test():
    model_dir = "android-app/app/src/main/assets/sherpa-onnx"
    tokens = os.path.join(model_dir, "tokens.txt")
    encoder = os.path.join(model_dir, "encoder-epoch-99-avg-1.int8.onnx")
    decoder = os.path.join(model_dir, "decoder-epoch-99-avg-1.onnx")
    joiner = os.path.join(model_dir, "joiner-epoch-99-avg-1.int8.onnx")

    print("正在加载 sherpa-onnx 离线引擎...")
    recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=tokens,
        encoder=encoder,
        decoder=decoder,
        joiner=joiner,
        num_threads=2,
        sample_rate=16000,
        feature_dim=80,
        decoding_method="modified_beam_search",
        max_active_paths=4,
        hotwords_score=1.8
    )

    wav_files = [
        ("音频1: 2026年10月30日 22点27分.mp3", "process/test1.wav"),
        ("音频2: 2026年10月30日 22点27分(2).mp3", "process/test2.wav")
    ]

    output_log = []

    for label, wav_path in wav_files:
        if not os.path.exists(wav_path):
            continue

        samples, sr = sf.read(wav_path, dtype="float32")
        duration = len(samples) / sr

        # 模式一：无热词直接解码
        stream = recognizer.create_stream()
        stream.accept_waveform(sr, samples)
        tail_paddings = [0.0] * int(sr * 0.5)
        stream.accept_waveform(sr, tail_paddings)
        while recognizer.is_ready(stream):
            recognizer.decode_stream(stream)
        result_no_hotwords = recognizer.get_result(stream)

        # 模式二：注入本地歌名/指令热词解码
        hotwords = "我 想 听/播 放/暮 色 回 响/投 食 歌/青 花 瓷/稻 香/白 鸽 乌 鸦 相 爱 的 戏 码/下 载"
        stream_hw = recognizer.create_stream(hotwords=hotwords)
        stream_hw.accept_waveform(sr, samples)
        stream_hw.accept_waveform(sr, tail_paddings)
        while recognizer.is_ready(stream_hw):
            recognizer.decode_stream(stream_hw)
        result_with_hotwords = recognizer.get_result(stream_hw)

        entry = {
            "file": label,
            "duration": round(duration, 2),
            "no_hotwords": result_no_hotwords,
            "with_hotwords": result_with_hotwords
        }
        output_log.append(entry)

    import json
    with open("process/test_result.json", "w", encoding="utf-8") as f:
        json.dump(output_log, f, ensure_ascii=False, indent=2)
    print("Done writing process/test_result.json")

if __name__ == "__main__":
    run_test()
