@echo off
chcp 65001 >nul
title XiaoMusic

set "PATH=C:\app\ffmpeg\bin;%PATH%"

echo ====================================================
echo 正在启动 XiaoMusic 本地服务...
echo 访问地址: http://localhost:58090
echo ====================================================

.\.venv\Scripts\python.exe -m xiaomusic.cli --port 58090 --ffmpeg_location "C:\app\ffmpeg\bin"
pause

