import urllib.request
import json
import re
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

output_file = r'c:\develop\xiaomusic\android-app\app\src\main\assets\song_lexicon.txt'
all_songs = set()

# 包含本地测试用热歌确保基础基准
all_songs.add('暮色回响')
all_songs.add('投食歌')

def download_and_extract_all(num):
    url = f"https://ghproxy.net/https://raw.githubusercontent.com/dengxiuqi/ChineseLyrics/master/lyrics{num}.json"
    print(f"[{num}/5] 开始下载并提取全部歌名: lyrics{num}.json ...")
    local_names = set()
    
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = resp.read()
            print(f"[{num}/5] 下载完成 ({len(data)/1024/1024:.1f} MB)，开始完整解析 JSON...")
            records = json.loads(data.decode('utf-8', errors='ignore'))
            for item in records:
                if isinstance(item, dict) and "name" in item:
                    raw_name = str(item["name"]).strip()
                    if raw_name:
                        # 纯清洗外部包裹的书名号《》
                        clean_name = raw_name.strip("《》【】[]()（）\"' ")
                        if clean_name:
                            local_names.add(clean_name)
                        else:
                            local_names.add(raw_name)
            print(f"[{num}/5] 全部解析完毕！提取到 {len(local_names)} 个歌名")
            return local_names
    except Exception as e:
        print(f"[{num}/5] 遇到异常，尝试流式正则提取全部: {e}")
        # 降级流式正则方案
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=120) as resp:
                text = resp.read().decode('utf-8', errors='ignore')
                matches = re.findall(r'"name":\s*"([^"]+)"', text)
                for m in matches:
                    clean_m = m.strip("《》【】[]()（）\"' ")
                    local_names.add(clean_m if clean_m else m)
            print(f"[{num}/5] 流式正则全量提取完毕！提取到 {len(local_names)} 个歌名")
            return local_names
        except Exception as e2:
            print(f"[{num}/5] 失败: {e2}")
            return local_names

print("=========================================================")
print("开始无遗漏提取目标库 [dengxiuqi/ChineseLyrics] 的所有歌名")
print("=========================================================")
start_time = time.time()

with ThreadPoolExecutor(max_workers=5) as executor:
    futures = [executor.submit(download_and_extract_all, i) for i in range(1, 6)]
    for f in as_completed(futures):
        names = f.result()
        all_songs.update(names)

print(f"\n全部 5 个文件提取完成！")
print(f"去重后总歌名数: {len(all_songs)} 首")

# 确保目标目录存在
os.makedirs(os.path.dirname(output_file), exist_ok=True)

# 全量写入 assets/song_lexicon.txt
with open(output_file, 'w', encoding='utf-8') as f:
    for song in sorted(all_songs):
        f.write(song + '\n')

file_size = os.path.getsize(output_file)
print(f"\n全部歌名已写入: {output_file}")
print(f"文件大小: {file_size} 字节 ({file_size / 1024:.2f} KB / {file_size / 1024 / 1024:.2f} MB)")
